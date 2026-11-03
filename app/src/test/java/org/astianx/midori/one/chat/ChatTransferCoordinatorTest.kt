package org.astianx.midori.one.chat

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ChatTransferCoordinatorTest {
    @Test fun slowFailingUploadAllowsDurableReceptionAndPreservesItAfterFailure() = exercise(false)
    @Test fun cancellingSlowUploadAllowsDurableReceptionAndRunsCleanup() = exercise(true)

    @Test fun obsoleteTransferCannotPublishAnErrorIntoTheNewSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stateMutex = Mutex()
        var current = true
        var failure: Exception? = null
        var cleaned = false
        val coordinator = ChatTransferCoordinator(scope, Dispatchers.IO, stateMutex) { failure = it }
        try {
            val job = coordinator.start(
                prepare = { Unit },
                transfer = {
                    started.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    throw IOException("Old account's failed upload")
                    @Suppress("UNREACHABLE_CODE") Unit
                },
                commit = { _, _ -> fail("An obsolete transfer must not commit") },
                isCurrent = { current }, cleanup = { _, _ -> cleaned = true }
            )
            assertTrue(started.await(5, TimeUnit.SECONDS))
            stateMutex.withLock { current = false }
            release.countDown()
            withTimeout(5_000) { job.join() }
            assertNull(failure)
            assertTrue(cleaned)
        } finally { release.countDown(); scope.cancel() }
    }

    private fun exercise(cancel: Boolean) = runBlocking {
        val stateMutex = Mutex()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        var failure: Exception? = null
        var committed = false
        val records = object : ChatRecords {
            val data = mutableMapOf<String, String>()
            override fun get(key: String) = data[key]
            override fun put(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
            override fun keys(prefix: String) = data.keys.filter { it.startsWith(prefix) }.toSet()
        }
        val coordinator = ChatTransferCoordinator(scope, Dispatchers.IO, stateMutex) { failure = it }
        try {
            val upload = coordinator.start(
                prepare = { "synthetic upload" },
                transfer = { _: String ->
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    throw IOException("Injected upload failure")
                    @Suppress("UNREACHABLE_CODE") Unit
                },
                commit = { _, _ -> committed = true },
                cleanup = { _, _ -> cleaned.countDown() }
            )
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val message = ChatMessage("durable", "bob@example.org", "received during upload", false, 1, sender = "bob@example.org")
            withTimeout(2_000) {
                stateMutex.withLock {
                    val repository = ChatHistoryRepository(records)
                    repository.accept("alice@example.org", message)
                    repository.save(ChatUiState(account = "alice@example.org", messages = listOf(message)))
                }
            }
            if (cancel) coordinator.cancel() else release.countDown()
            withTimeout(5_000) { joinAll(upload) }
            assertTrue(cleaned.await(1, TimeUnit.SECONDS))
            assertFalse(committed)
            if (cancel) assertNull(failure) else assertTrue(failure is IOException)
            assertEquals(listOf(message), ChatHistoryRepository(records).load("alice@example.org").messages)
        } finally { release.countDown(); scope.cancel() }
    }
}
