package org.astianx.midori.one.chat

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated SQLCipher feasibility gate. This never opens/migrates production history or keys. */
@RunWith(AndroidJUnit4::class)
class EncryptedHistoryPrototypeTest {
    private class Lab(val context: Context) : AutoCloseable {
        val name = "history-lab-${UUID.randomUUID()}"
        val directory = File(context.noBackupFilesDir, name).apply { check(mkdir()) }
        val file = File(directory, "history.db")
        private val envelopeName = "$name-key"
        private val envelopeFile = File(context.noBackupFilesDir, "$envelopeName.enc")
        private val vault = ChatVault(context, envelopeName)
        private fun password(): ByteArray {
            val saved = vault.get("database-key") ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.let { raw ->
                try { Base64.getEncoder().encodeToString(raw).also { vault.put("database-key", it) } }
                finally { raw.fill(0) }
            }
            return Base64.getDecoder().decode(saved)
        }
        fun open(target: File = file): SQLiteDatabase {
            // Instrumentation runs in the target process, whose production APK has no SQLCipher.
            // Load the library from the test APK rather than changing production packaging.
            val testApp = InstrumentationRegistry.getInstrumentation().context.applicationInfo
            val extracted = File(testApp.nativeLibraryDir, "libsqlcipher.so")
            if (extracted.isFile) System.load(extracted.path)
            else System.load(testApp.sourceDir + "!/lib/" + android.os.Build.SUPPORTED_ABIS.first() + "/libsqlcipher.so")
            val key = password()
            return try {
                SQLiteDatabase.openOrCreateDatabase(target, key, null, null).apply {
                    execSQL("PRAGMA temp_store=MEMORY")
                    execSQL("PRAGMA synchronous=FULL")
                    execSQL("PRAGMA cipher_memory_security=ON")
                    rawQuery("PRAGMA journal_mode=WAL", emptyArray()).use { check(it.moveToFirst()); check(it.getString(0) == "wal") }
                    rawQuery("PRAGMA wal_autocheckpoint=0", emptyArray()).use { check(it.moveToFirst()); check(it.getInt(0) == 0) }
                }
            } finally { key.fill(0) }
        }
        fun schema(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE messages(sequence INTEGER PRIMARY KEY, account TEXT NOT NULL, peer TEXT NOT NULL, sender TEXT NOT NULL, stanza_id TEXT NOT NULL, stamp INTEGER NOT NULL, body TEXT NOT NULL, UNIQUE(account,peer,sender,stanza_id))")
            db.execSQL("CREATE INDEX history_page ON messages(account,peer,stamp DESC,sequence DESC)")
            db.execSQL("CREATE VIRTUAL TABLE message_search USING fts5(body)")
            db.execSQL("CREATE TRIGGER search_insert AFTER INSERT ON messages BEGIN INSERT INTO message_search(rowid,body) VALUES(new.sequence,new.body); END")
            db.execSQL("CREATE TABLE checkpoints(account TEXT PRIMARY KEY, cursor TEXT NOT NULL)")
        }
        fun add(db: SQLiteDatabase, id: Int, account: String = "alice", peer: String = "bob", body: String = "syntheticmessage $id") {
            db.execSQL("INSERT INTO messages(account,peer,sender,stanza_id,stamp,body) VALUES(?,?,?,?,?,?)",
                arrayOf(account, peer, peer, "id-$id", id / 5, body))
        }
        override fun close() {
            directory.deleteRecursively()
            envelopeFile.delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("midori.${envelopeName.replace('-', '.')}") }
        }
    }

    @Test fun fiftyThousandMessagesHaveStableBoundedPagesAndAccountScopedFullTextSearch() {
        Lab(InstrumentationRegistry.getInstrumentation().targetContext).use { lab ->
            val started = System.nanoTime()
            lab.open().use { db ->
                lab.schema(db)
                db.beginTransaction()
                try {
                    repeat(50_000) { lab.add(db, it) }
                    lab.add(db, 0, account = "other", body = "syntheticmessage 49999 private other account")
                    lab.add(db, 0, peer = "carol", body = "syntheticmessage 49999 other conversation")
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                var timestamp = Long.MAX_VALUE
                var sequence = Long.MAX_VALUE
                var total = 0
                var previousSequence = Long.MAX_VALUE
                while (true) {
                    var count = 0
                    db.rawQuery("SELECT sequence,stamp FROM messages WHERE account=? AND peer=? AND (stamp,sequence)<(?,?) ORDER BY stamp DESC,sequence DESC LIMIT 100",
                        arrayOf("alice", "bob", "$timestamp", "$sequence")).use { page ->
                        while (page.moveToNext()) {
                            val next = page.getLong(0)
                            assertTrue("A stable page repeated/skipped its position", next < previousSequence)
                            previousSequence = next; sequence = next; timestamp = page.getLong(1)
                            count++; total++
                        }
                    }
                    assertTrue(count <= 100)
                    if (count == 0) break
                }
                assertEquals(50_000, total)
                db.rawQuery("SELECT m.body FROM message_search f JOIN messages m ON m.sequence=f.rowid WHERE message_search MATCH ? AND m.account=? AND m.peer=? ORDER BY m.stamp DESC,m.sequence DESC LIMIT 100",
                    arrayOf("\"syntheticmessage\" AND \"49999\"", "alice", "bob")).use {
                    assertTrue(it.moveToFirst()); assertEquals("syntheticmessage 49999", it.getString(0)); assertFalse(it.moveToNext())
                }
                db.rawQuery("EXPLAIN QUERY PLAN SELECT sequence FROM messages WHERE account=? AND peer=? AND (stamp,sequence)<(?,?) ORDER BY stamp DESC,sequence DESC LIMIT 100",
                    arrayOf("alice", "bob", "10000", "50000")).use {
                    var indexed = false
                    while (it.moveToNext()) indexed = indexed || it.getString(3).contains("history_page")
                    assertTrue("Paging must use the conversation index", indexed)
                }
                db.rawQuery("PRAGMA cipher_integrity_check", emptyArray()).use { assertFalse(it.moveToFirst()) }
                db.rawQuery("PRAGMA integrity_check", emptyArray()).use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            }
            lab.open().use { db ->
                db.rawQuery("SELECT count(*) FROM messages WHERE account='alice' AND peer='bob'", emptyArray()).use {
                    assertTrue(it.moveToFirst()); assertEquals(50_000, it.getInt(0))
                }
            }
            android.util.Log.i("MidoriStorageLab", "messages=50000 page_size=100 elapsed_ms=${(System.nanoTime() - started) / 1_000_000}")
        }
    }

    @Test fun databaseWalAndEncryptedBackupContainNoFixturePlaintextAndWrongKeyIsRejected() {
        Lab(InstrumentationRegistry.getInstrumentation().targetContext).use { lab ->
            val sentinel = "midori-secret-sentinel-${UUID.randomUUID()}"
            lab.open().use { db ->
                lab.schema(db); lab.add(db, 1, body = sentinel)
                db.rawQuery("PRAGMA temp_store", emptyArray()).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
                val wal = File(lab.file.path + "-wal")
                assertTrue(wal.isFile && wal.length() > 0)
                for (file in listOf(lab.file, wal)) {
                    assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(sentinel))
                    assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("CREATE TABLE messages"))
                }
                assertFalse(lab.directory.listFiles().orEmpty().any { it.name.contains("temp") })
            }
            val backup = File(lab.directory, "backup.enc")
            lab.file.copyTo(backup)
            assertFalse(String(backup.readBytes(), Charsets.ISO_8859_1).contains(sentinel))
            lab.open(backup).use { db ->
                db.rawQuery("SELECT body FROM messages", emptyArray()).use { assertTrue(it.moveToFirst()); assertEquals(sentinel, it.getString(0)) }
            }
            val wrong = ByteArray(32).also { SecureRandom().nextBytes(it) }
            try {
                assertThrows(Exception::class.java) {
                    SQLiteDatabase.openDatabase(lab.file.path, wrong, null, SQLiteDatabase.OPEN_READONLY, null).use {
                        it.rawQuery("SELECT count(*) FROM messages", emptyArray()).use { result -> result.moveToFirst() }
                    }
                }
            } finally { wrong.fill(0) }
        }
    }

    @Test fun faultsBeforeCommitRollBackBothMessagesAndCursorAndCommittedPageSurvivesReopen() {
        Lab(InstrumentationRegistry.getInstrumentation().targetContext).use { lab ->
            lab.open().use { lab.schema(it) }
            repeat(3) { fault ->
                lab.open().use { db ->
                    db.beginTransaction()
                    try {
                        if (fault == 0) throw java.io.IOException("before message")
                        lab.add(db, 1)
                        if (fault == 1) throw java.io.IOException("before cursor")
                        db.execSQL("INSERT INTO checkpoints VALUES('alice','next')")
                        throw java.io.IOException("before commit")
                    } catch (_: java.io.IOException) {
                        // Model abrupt termination before COMMIT; SQLite discards the transaction.
                    } finally { db.endTransaction() }
                }
                lab.open().use { db ->
                    for (table in listOf("messages", "checkpoints")) db.rawQuery("SELECT count(*) FROM $table", emptyArray()).use {
                        assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                    }
                }
            }
            lab.open().use { db ->
                db.beginTransaction()
                try { lab.add(db, 1); db.execSQL("INSERT INTO checkpoints VALUES('alice','committed')"); db.setTransactionSuccessful() }
                finally { db.endTransaction() }
            }
            lab.open().use { db ->
                db.rawQuery("SELECT cursor,(SELECT count(*) FROM messages) FROM checkpoints", emptyArray()).use {
                    assertTrue(it.moveToFirst()); assertEquals("committed", it.getString(0)); assertEquals(1, it.getInt(1))
                }
            }
        }
    }
}
