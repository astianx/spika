package org.astianx.midori.one.chat

import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smackx.omemo.element.OmemoElement_VAxolotl
import org.jivesoftware.smackx.omemo.element.OmemoHeaderElement_VAxolotl
import org.jivesoftware.smackx.omemo.element.OmemoKeyElement
import org.junit.Assert.*
import org.junit.Test

class PreparedSendStoreTest {
    @org.junit.Before fun initializeSmack() { org.jivesoftware.smack.java7.Java7SmackInitializer().initialize() }
    private val account = "alice@example.org"
    private val peer = "bob@example.org"
    private class Records : ChatRecords {
        val data = mutableMapOf<String, String>()
        var failCommit = false
        var failWrite = false
        override fun get(key: String) = data[key]
        override fun keys(prefix: String) = data.keys.filter { it.startsWith(prefix) }.toSet()
        override fun put(key: String, value: String?) {
            if (failWrite) throw java.io.IOException("Injected write failure")
            if (value == null) data.remove(key) else data[key] = value
        }
        override fun <T> durableBatch(block: () -> T): T {
            val before = data.toMap()
            val result = block()
            if (failCommit) {
                data.clear(); data.putAll(before)
                throw java.io.IOException("Injected commit failure")
            }
            return result
        }
    }
    private fun encrypted(id: String): Message {
        val element = OmemoElement_VAxolotl(OmemoHeaderElement_VAxolotl(7,
            listOf(OmemoKeyElement(ByteArray(32) { 4 }, 8)), ByteArray(12) { 3 }), ByteArray(64) { 9 })
        return StanzaBuilder.buildMessage(id).to(peer).ofType(Message.Type.chat).addExtension(element).build()
    }

    @Test fun restartReusesTheExactCiphertextWithoutAnotherRatchetAdvance() {
        val records = Records()
        var encryptions = 0
        val first = PreparedSendStore(records, account).prepare(peer, "stable", "trusted snapshot") {
            encryptions++; records.put("ratchet", "advanced"); encrypted("stable")
        }
        assertEquals("advanced", records.get("ratchet"))
        val retry = PreparedSendStore(records, account).prepare(peer, "stable", "trusted snapshot") {
            encryptions++; error("A prepared send must not encrypt again")
        }
        assertEquals(1, encryptions)
        assertEquals(first.toXML().toString(), retry.toXML().toString())
        assertNull(retry.body)
    }

    @Test fun changedMembershipOrDeviceSnapshotBlocksReemission() {
        val records = Records(); val store = PreparedSendStore(records, account)
        store.prepare(peer, "stable", "original members/devices") { encrypted("stable") }
        assertThrows(ChatException::class.java) {
            store.prepare(peer, "stable", "changed members/devices") { error("must not encrypt") }
        }
        assertEquals(1, records.keys("prepared/").size)
    }

    @Test fun commitFailureCannotReturnCiphertextAndDoesNotPersistRatchetAdvance() {
        val records = Records(); records.put("ratchet", "original"); records.failCommit = true
        assertThrows(java.io.IOException::class.java) {
            PreparedSendStore(records, account).prepare(peer, "stable", "snapshot") {
                records.put("ratchet", "advanced"); encrypted("stable")
            }
        }
        assertEquals("original", records.get("ratchet"))
        assertTrue(records.keys("prepared/").isEmpty())
    }

    @Test fun failedConfirmationPreservesPreparedCiphertext() {
        val records = Records(); val store = PreparedSendStore(records, account)
        store.prepare(peer, "stable", "snapshot") { encrypted("stable") }
        records.failWrite = true
        assertThrows(java.io.IOException::class.java) { store.confirmed(peer, "stable") }
        assertEquals(1, records.keys("prepared/").size)
    }
}
