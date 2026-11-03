package org.astianx.midori.one.chat

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDurabilityTest {
    private val dispatcher = StandardTestDispatcher()
    private val alice = "alice@example.org"
    private val bob = "bob@example.org"
    private class Records : ChatRecords {
        val data = mutableMapOf<String, String>()
        var fail = false
        override fun get(key: String) = data[key]
        override fun keys(prefix: String) = data.keys.filter { it.startsWith(prefix) }.toSet()
        override fun put(key: String, value: String?) {
            if (fail) throw IOException("Injected disk failure")
            if (value == null) data.remove(key) else data[key] = value
        }
    }
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    private fun received(id: String = "same", sender: String = bob) =
        ChatMessage(id, bob, "durable", false, 20, sender = sender)

    @Test fun callbackIsDurableBeforeTheUiJobRunsAndSurvivesRestart() = runTest(dispatcher) {
        val records = Records()
        lateinit var callback: ChatEvents
        val transport = object : StubChatTransport() {
            override fun connect(account: ChatAccount, events: ChatEvents) { callback = events }
        }
        val model = ChatViewModel(records, { transport }, io = dispatcher)
        advanceUntilIdle()
        model.connect(alice, "synthetic", "example.org", "5222"); runCurrent()
        callback.message(received(), false)
        // No scheduler advance: the StateFlow projection has not accepted the message yet.
        assertTrue(model.state.value.messages.isEmpty())
        assertEquals(listOf(received()), ChatHistoryRepository(records).load(alice).messages)
        advanceUntilIdle()
        assertEquals(listOf(received()), model.state.value.messages)
        assertTrue(records.keys("inbound/").isEmpty())
        assertEquals(listOf(received()), ChatHistoryRepository(records).load(alice).messages)
    }

    @Test fun failedDiskWriteRejectsTheCallbackAndCannotAdvanceArchiveCheckpoint() = runTest(dispatcher) {
        val records = Records()
        lateinit var callback: ChatEvents
        val model = ChatViewModel(records, { object : StubChatTransport() {
            override fun connect(account: ChatAccount, events: ChatEvents) { callback = events }
        } }, io = dispatcher)
        advanceUntilIdle(); model.connect(alice, "synthetic", "example.org", "5222"); advanceUntilIdle()
        val checkpoint = ChatArchiveCoordinator(records, "archive/$alice")
        checkpoint.commitPage(10, "previous")
        records.fail = true
        var receipt = false
        assertThrows(IOException::class.java) {
            callback.message(received(), true)
            checkpoint.commitPage(20, "next")
            receipt = true
        }
        assertFalse(receipt)
        assertEquals("previous", checkpoint.cursor)
        assertEquals(10L, checkpoint.timestamp)
        advanceUntilIdle()
        assertTrue(model.state.value.messages.isEmpty())
    }

    @Test fun deduplicationIsScopedByAccountConversationSenderAndId() {
        val records = Records(); val journal = ChatInboundJournal(records)
        journal.accept(alice, received()); journal.accept(alice, received())
        journal.accept(alice, received(sender = "carol@example.org"))
        journal.accept(alice, received().copy(peer = "other@example.org"))
        journal.accept("other@example.org", received())
        assertEquals(3, journal.pending(alice).size)
        assertEquals(1, journal.pending("other@example.org").size)
    }

    @Test fun unreadableRedeliveryCannotReplaceDurableReadableContent() {
        val journal = ChatInboundJournal(Records())
        journal.accept(alice, received().copy(body = "", protection = MessageProtection.UNREADABLE))
        journal.accept(alice, received())
        journal.accept(alice, received().copy(body = "", protection = MessageProtection.UNREADABLE))
        assertEquals(listOf(received()), journal.pending(alice))
    }

    @Test fun failedProjectionKeepsTheJournalForRecovery() {
        val records = Records(); val repository = ChatHistoryRepository(records)
        repository.accept(alice, received())
        records.fail = true
        assertThrows(IOException::class.java) { repository.save(ChatUiState(account = alice, messages = listOf(received()))) }
        records.fail = false
        assertEquals(listOf(received()), repository.load(alice).messages)
    }

    @Test fun offlineOutboxSurvivesRestartAndSendsTheSameIdWhenReconnected() = runTest(dispatcher) {
        val records = Records()
        records.put("settings/background", "false")
        records.put("session", org.json.JSONObject().put("address", alice).put("password", "synthetic")
            .put("host", "example.org").put("port", 5222).toString())
        val sent = mutableListOf<String>()
        val transport = object : StubChatTransport() {
            override fun send(peer: String, body: String, id: String) { sent += id }
        }
        val first = ChatViewModel(records, { transport }, io = dispatcher, initialForeground = false)
        advanceUntilIdle(); first.open(bob); advanceUntilIdle()
        first.updateDraft("offline"); advanceUntilIdle(); first.send("offline"); advanceUntilIdle()
        val pending = first.state.value.messages.single()
        assertEquals(MessageDelivery.PENDING, pending.delivery)
        assertTrue(sent.isEmpty())
        assertFalse(first.state.value.drafts.containsKey(bob))
        val restarted = ChatViewModel(records, { transport }, io = dispatcher, initialForeground = false)
        advanceUntilIdle()
        assertEquals(pending.id, restarted.state.value.messages.single().id)
        restarted.retry(); advanceUntilIdle()
        assertEquals(listOf(pending.id), sent)
        assertEquals(1, restarted.state.value.messages.size)
        assertEquals(MessageDelivery.SENT, restarted.state.value.messages.single().delivery)
    }

    @Test fun aFailedRetryCannotErasePriorGroupUncertainty() = runTest(dispatcher) {
        val records = Records()
        var fail: Exception? = UncertainSendException(IOException("lost group socket"))
        val client = object : StubChatTransport() {
            override fun send(peer: String, body: String, id: String) { fail?.let { throw it } }
        }
        val model = ChatViewModel(records, { client }, io = dispatcher)
        advanceUntilIdle(); model.connect(alice, "synthetic", "example.org", "5222"); advanceUntilIdle()
        model.open("room@conference.example.org"); advanceUntilIdle(); model.send("group message"); advanceUntilIdle()
        val original = model.state.value.messages.single()
        assertEquals(MessageDelivery.UNCERTAIN, original.delivery)
        fail = ChatException(org.astianx.midori.one.UiText(org.astianx.midori.one.R.string.chat_group_changed))
        model.retryMessage(original); advanceUntilIdle()
        assertEquals(original.id, model.state.value.messages.single().id)
        assertEquals(MessageDelivery.UNCERTAIN, model.state.value.messages.single().delivery)
    }
}

internal open class StubChatTransport : ChatTransport {
    override fun connect(account: ChatAccount, events: ChatEvents) { events.connection(ChatConnection.ONLINE) }
    override fun disconnect() {}
    override fun send(peer: String, body: String, id: String) {}
    override fun devices(peer: String) = emptyList<ChatDevice>()
    override fun trust(device: ChatDevice, trusted: Boolean) {}
    override fun addContact(peer: String) {}
    override fun answerSubscription(peer: String, approve: Boolean) {}
    override fun block(peer: String, blocked: Boolean) {}
    override fun markRead(peer: String, id: String) {}
    override fun setActive(active: Boolean) {}
}
