package org.astianx.midori.one.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.astianx.midori.one.R
import org.astianx.midori.one.UiText
import org.jivesoftware.smack.sasl.SASLErrorException
import org.jivesoftware.smack.sasl.packet.SaslNonza.SASLFailure
import org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException
import org.jivesoftware.smackx.omemo.internal.OmemoDevice
import org.jxmpp.jid.impl.JidCreate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatReliabilityTest {
    private val dispatcher = StandardTestDispatcher()
    private val alice = "alice@example.org"
    private val bob = "bob@example.org"

    private class Records : ChatRecords {
        val data = mutableMapOf<String, String>()
        override fun get(key: String) = data[key]
        override fun put(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
        override fun keys(prefix: String) = data.keys.filter { it.startsWith(prefix) }.toSet()
    }

    private class Transport : ChatTransport {
        lateinit var events: ChatEvents
        val sent = mutableListOf<Pair<String, String>>()
        var connectError: Exception? = null
        var sendError: Exception? = null
        var deviceList = emptyList<ChatDevice>()
        override fun connect(account: ChatAccount, events: ChatEvents) {
            this.events = events; connectError?.let { throw it }; events.connection(ChatConnection.ONLINE)
        }
        override fun disconnect() {}
        override fun send(peer: String, body: String, id: String) { sent += id to body; sendError?.let { throw it } }
        override fun devices(peer: String) = deviceList
        override fun trust(device: ChatDevice, trusted: Boolean) {
            deviceList = deviceList.map { if (it == device) it.copy(trusted = trusted) else it }
        }
        override fun addContact(peer: String) {}
        override fun answerSubscription(peer: String, approve: Boolean) {}
        override fun block(peer: String, blocked: Boolean) {}
        override fun markRead(peer: String, id: String) {}
        override fun setActive(active: Boolean) {}
    }

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private suspend fun kotlinx.coroutines.test.TestScope.signedIn(
        records: Records = Records(), client: Transport = Transport(),
        register: (ChatAccount) -> Unit = {}
    ): ChatViewModel {
        val vm = ChatViewModel(records, { client }, io = dispatcher, registerAccount = register)
        advanceUntilIdle()
        vm.connect(alice, "password", "example.org", "5222"); advanceUntilIdle()
        vm.open(bob); advanceUntilIdle()
        return vm
    }

    private fun message(body: String = "hi", reply: ReplyReference? = null) =
        ChatMessage("m1", bob, body, true, 1, replyTo = reply, sender = alice)

    @Test fun replyIsAnAdditiveFieldOfPayloadVersionOne() {
        val quote = ReplyReference("abc-1", bob, "the original")
        val encoded = ChatPayload.encode(message("answer", quote))
        assertTrue(encoded.startsWith("midori:content:1\n"))
        val decoded = ChatPayload.decode(ChatMessage("m2", bob, encoded, false, 1))
        assertEquals("answer", decoded.body)
        assertEquals(quote, decoded.replyTo)
        // A reader that ignores `reply` still gets the text: only optional fields were added.
        val withoutReply = JSONObject(encoded.removePrefix("midori:content:1\n")).apply { remove("reply") }
        assertEquals("answer", ChatPayload.decode(ChatMessage("m3", bob, "midori:content:1\n$withoutReply", false, 1)).body)
        assertEquals(message("plain").body, ChatPayload.encode(message("plain")))
    }

    @Test fun unknownPayloadVersionsAndMalformedQuotesNeverShowRawJsonOrLoseText() {
        val future = ChatPayload.decode(ChatMessage("m", bob, "midori:content:2\n{\"text\":\"x\"}", false, 1))
        assertEquals(MessageProtection.UNREADABLE, future.protection)
        assertEquals("", future.body)
        for (bad in listOf("""{"id":"a b","from":"$bob","text":"t"}""", """{"id":"ok","from":"not a jid/","text":"t"}""", """{"from":"$bob"}""")) {
            val decoded = ChatPayload.decode(ChatMessage("m", bob, "midori:content:1\n{\"text\":\"kept\",\"reply\":$bad}", false, 1))
            assertEquals("kept", decoded.body)
            assertNull(decoded.replyTo)
            assertEquals(MessageProtection.ENCRYPTED, decoded.protection)
        }
        val long = ChatPayload.readReply(JSONObject().put("id", "x").put("from", bob).put("text", "y".repeat(500)))!!
        assertEquals(ChatPayload.REPLY_PREVIEW_CHARS, long.preview.length)
    }

    @Test fun repliesCarryABoundedQuoteAndClearTheComposer() = runTest(dispatcher) {
        val client = Transport(); val vm = signedIn(client = client)
        client.events.message(ChatMessage("orig-1", bob, "z".repeat(400), false, 5, sender = bob), false); advanceUntilIdle()
        val original = vm.state.value.messages.single()
        vm.startReply(original)
        assertEquals(original, vm.state.value.replyingTo)
        vm.send("my answer"); advanceUntilIdle()
        val sent = vm.state.value.messages.last()
        assertEquals(ReplyReference("orig-1", bob, "z".repeat(ChatPayload.REPLY_PREVIEW_CHARS)), sent.replyTo)
        assertNull(vm.state.value.replyingTo)
        assertTrue(client.sent.single().second.startsWith("midori:content:1\n"))
        vm.send("not a reply"); advanceUntilIdle()
        assertNull(vm.state.value.messages.last().replyTo)
        vm.startReply(original); vm.cancelReply()
        assertNull(vm.state.value.replyingTo)
    }

    @Test fun replyingIsRefusedInGroupsAndForUnreadableMessages() = runTest(dispatcher) {
        val client = Transport(); val vm = signedIn(client = client)
        client.events.message(ChatMessage("bad", bob, "", false, 1, protection = MessageProtection.UNREADABLE), true); advanceUntilIdle()
        vm.startReply(vm.state.value.messages.single())
        assertNull(vm.state.value.replyingTo)
        client.events.group(ChatGroup("room@conference.example.org", "Room", setOf(alice, bob), true)); advanceUntilIdle()
        vm.open("room@conference.example.org"); advanceUntilIdle()
        client.events.message(ChatMessage("g1", "room@conference.example.org", "hello all", false, 2), false); advanceUntilIdle()
        vm.startReply(vm.state.value.messages.last())
        assertNull(vm.state.value.replyingTo)
    }

    @Test fun aSendWhoseStanzaMayHaveLeftIsUncertainAndItsRetryIsIdempotent() = runTest(dispatcher) {
        val client = Transport(); val vm = signedIn(client = client)
        client.sendError = UncertainSendException(java.io.IOException("socket closed"))
        vm.send("maybe delivered"); advanceUntilIdle()
        val uncertain = vm.state.value.messages.single()
        assertEquals(MessageDelivery.UNCERTAIN, uncertain.delivery)
        client.sendError = null
        vm.retryMessage(uncertain); advanceUntilIdle()
        assertEquals(listOf(uncertain.id, uncertain.id), client.sent.map { it.first })
        assertEquals(1, vm.state.value.messages.size)
        assertEquals(MessageDelivery.SENT, vm.state.value.messages.single().delivery)
    }

    @Test fun aDeliveryReceiptProvesAnUncertainMessageArrived() = runTest(dispatcher) {
        val client = Transport(); val vm = signedIn(client = client)
        client.sendError = UncertainSendException(java.io.IOException("socket closed"))
        vm.send("maybe"); advanceUntilIdle()
        val id = vm.state.value.messages.single().id
        client.events.receipt(bob, id, MessageDelivery.DELIVERED); advanceUntilIdle()
        assertEquals(MessageDelivery.DELIVERED, vm.state.value.messages.single().delivery)
    }

    @Test fun restartTurnsInterruptedSendsIntoUncertainAndTheArchiveReconcilesThem() = runTest(dispatcher) {
        val records = Records(); val first = Transport(); val vm = signedIn(records, first)
        vm.send("killed mid-send"); advanceUntilIdle()
        val id = vm.state.value.messages.single().id
        val key = "history/$alice"
        records.data[key] = records.data.getValue(key).replace("\"delivery\":\"SENT\"", "\"delivery\":\"SENDING\"")
        val second = Transport()
        val restarted = ChatViewModel(records, { second }, io = dispatcher)
        advanceUntilIdle()
        assertEquals(MessageDelivery.UNCERTAIN, restarted.state.value.messages.single().delivery)
        // The server archive returns our own copy (undecryptable here), proving it was sent.
        second.events.message(ChatMessage(id, bob, "", true, 9, protection = MessageProtection.UNREADABLE, sender = alice), true)
        advanceUntilIdle()
        assertEquals(1, restarted.state.value.messages.size)
        assertEquals(MessageDelivery.SENT, restarted.state.value.messages.single().delivery)
        assertEquals("killed mid-send", restarted.state.value.messages.single().body)
    }

    @Test fun anInterruptedSendDoesNotLeaveItsTextInTheComposerToBeSentAgain() = runTest(dispatcher) {
        val records = Records(); val vm = signedIn(records)
        vm.updateDraft("killed mid-send"); advanceUntilIdle()
        vm.send("killed mid-send"); advanceUntilIdle()
        // The process died before the draft was cleared: history holds a SENDING message and the same draft.
        val key = "history/$alice"
        val saved = JSONObject(records.data.getValue(key))
        saved.put("drafts", JSONObject().put(bob, "killed mid-send").put("carol@example.org", "unrelated draft"))
        records.data[key] = saved.toString().replace("\"delivery\":\"SENT\"", "\"delivery\":\"SENDING\"")
        val restarted = ChatViewModel(records, { Transport() }, io = dispatcher)
        advanceUntilIdle()
        assertEquals(MessageDelivery.UNCERTAIN, restarted.state.value.messages.single().delivery)
        assertNull(restarted.state.value.drafts[bob])
        assertEquals("unrelated draft", restarted.state.value.drafts["carol@example.org"])
    }

    @Test fun anUndecidedDeviceExplainsVerificationAndApprovalSendsTheWaitingMessageOnce() = runTest(dispatcher) {
        val device = ChatDevice(bob, 7, "ab".repeat(32), false)
        val client = Transport().apply { deviceList = listOf(device) }
        val vm = signedIn(client = client)
        vm.checkPeerTrust(); advanceUntilIdle()
        assertEquals(bob, vm.state.value.verificationPeer)
        client.sendError = UndecidedOmemoIdentityException(OmemoDevice(JidCreate.entityBareFrom(bob), 7))
        vm.updateDraft("first message")
        vm.send("first message"); advanceUntilIdle()
        val blocked = vm.state.value.messages.single()
        assertEquals(MessageDelivery.FAILED, blocked.delivery)
        assertEquals(UiText(R.string.chat_verify_first), vm.state.value.error)
        client.sendError = null
        vm.loadDevices(); advanceUntilIdle()
        vm.trust(vm.state.value.devices.single(), true); advanceUntilIdle()
        assertNull(vm.state.value.verificationPeer)
        assertEquals(listOf(blocked.id, blocked.id), client.sent.map { it.first })
        assertEquals(MessageDelivery.SENT, vm.state.value.messages.single().delivery)
        assertNull("The draft behind the resent message is cleared", vm.state.value.drafts[bob])
        vm.trust(vm.state.value.devices.single(), true); advanceUntilIdle()
        assertEquals(2, client.sent.size)
    }

    @Test fun trustedDevicesNeverShowTheVerificationBanner() = runTest(dispatcher) {
        val client = Transport().apply { deviceList = listOf(ChatDevice(bob, 1, "cd".repeat(32), true)) }
        val vm = signedIn(client = client)
        vm.checkPeerTrust(); advanceUntilIdle()
        assertNull(vm.state.value.verificationPeer)
    }

    private fun saslFailure() = SASLErrorException("PLAIN", SASLFailure("not-authorized"))

    @Test fun aLostRegistrationResponseResumesTheSameAccountInsteadOfCreatingAnother() = runTest(dispatcher) {
        val records = Records(); val client = Transport(); var created = 0
        val vm = ChatViewModel(records, { client }, io = dispatcher, registerAccount = { created++; throw java.io.IOException("reply lost") })
        advanceUntilIdle()
        vm.register(alice, "a-long-password-1", "example.org", "5222"); advanceUntilIdle()
        // The server did create it: signing in with the saved credentials succeeds.
        assertEquals(1, created)
        assertEquals(alice, vm.state.value.account)
        assertEquals(ChatConnection.ONLINE, vm.state.value.connection)
        assertNull(vm.state.value.pendingRegistration)
        assertNull(records.data["pending-registration"])
        assertNotNull(records.data["session"])
    }

    @Test fun anExistingNameWithOurPasswordIsTheAccountWeJustCreated() = runTest(dispatcher) {
        val records = Records(); val client = Transport()
        val vm = ChatViewModel(records, { client }, io = dispatcher,
            registerAccount = { throw ChatException(UiText(R.string.profile_username_taken)) })
        advanceUntilIdle()
        vm.register(alice, "a-long-password-1", "example.org", "5222"); advanceUntilIdle()
        assertEquals(alice, vm.state.value.account)
        assertNull(vm.state.value.error)
    }

    @Test fun aTakenNameWithAnotherPasswordIsReportedAndNothingStaysSignedIn() = runTest(dispatcher) {
        val records = Records(); val client = Transport().apply { connectError = saslFailure() }
        val vm = ChatViewModel(records, { client }, io = dispatcher,
            registerAccount = { throw ChatException(UiText(R.string.profile_username_taken)) })
        advanceUntilIdle()
        vm.register(alice, "a-long-password-1", "example.org", "5222"); advanceUntilIdle()
        assertEquals(UiText(R.string.profile_username_taken), vm.state.value.error)
        assertEquals("", vm.state.value.account)
        assertNull(vm.state.value.pendingRegistration)
        assertNull(records.data["pending-registration"])
        assertNull(records.data["session"])
    }

    @Test fun anUnknownOutcomeSurvivesRestartAndCanBeResumedOrDiscarded() = runTest(dispatcher) {
        val records = Records(); val offline = Transport().apply { connectError = java.io.IOException("offline") }
        val vm = ChatViewModel(records, { offline }, io = dispatcher, registerAccount = { throw java.io.IOException("reply lost") })
        advanceUntilIdle()
        vm.register(alice, "a-long-password-1", "example.org", "5222"); advanceUntilIdle()
        assertEquals("", vm.state.value.account)
        assertEquals(alice, vm.state.value.pendingRegistration)
        assertNotNull(records.data["pending-registration"])
        // A new process finds the saved intent and offers to continue with it.
        val online = Transport()
        val restarted = ChatViewModel(records, { online }, io = dispatcher)
        advanceUntilIdle()
        assertEquals(alice, restarted.state.value.pendingRegistration)
        restarted.resumeRegistration(); advanceUntilIdle()
        assertEquals(alice, restarted.state.value.account)
        assertNull(restarted.state.value.pendingRegistration)
        assertNull(records.data["pending-registration"])
    }

    @Test fun resumingAnAccountThatWasNeverCreatedExplainsItAndClearsTheIntent() = runTest(dispatcher) {
        val records = Records(); val client = Transport().apply { connectError = saslFailure() }
        val vm = ChatViewModel(records, { client }, io = dispatcher, registerAccount = { throw java.io.IOException("never arrived") })
        advanceUntilIdle()
        vm.register(alice, "a-long-password-1", "example.org", "5222"); advanceUntilIdle()
        assertEquals(alice, vm.state.value.pendingRegistration)
        vm.resumeRegistration(); advanceUntilIdle()
        assertEquals(UiText(R.string.profile_registration_not_created), vm.state.value.error)
        assertNull(vm.state.value.pendingRegistration)
        assertNull(records.data["pending-registration"])
    }

    @Test fun pilotRegistrationAsksOnlyForAUsernameAndKeepsAGeneratedPassword() = runTest(dispatcher) {
        val records = Records(); val client = Transport(); var seen: ChatAccount? = null
        val vm = ChatViewModel(records, { client }, io = dispatcher, registerAccount = { seen = it })
        advanceUntilIdle()
        val pilot = ChatPilot("pilot.example.org", "example.org", 5222)
        vm.registerPilot("Carol", pilot, "generated-secret-value-1234"); advanceUntilIdle()
        assertEquals(ChatAccount("carol@example.org", "generated-secret-value-1234", "pilot.example.org", 5222), seen)
        assertEquals("carol@example.org", vm.state.value.account)
        assertTrue(ChatPilot.newPassword().length >= 40)
        assertNotEquals(ChatPilot.newPassword(), ChatPilot.newPassword())
        val invalid = ChatViewModel(Records(), { client }, io = dispatcher)
        advanceUntilIdle()
        invalid.registerPilot("a", pilot); advanceUntilIdle()
        assertEquals(UiText(R.string.chat_pilot_username_invalid), invalid.state.value.error)
        invalid.registerPilot("valid.name", null); advanceUntilIdle()
        assertEquals(UiText(R.string.profile_registration_unavailable), invalid.state.value.error)
    }
}
