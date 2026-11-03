package org.astianx.midori.one.chat

import java.io.IOException
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StanzaBuilder
import org.junit.Assert.*
import org.junit.Test

class ConfirmedStanzaSenderTest {
    private class Backend(override val available: Boolean = true) : StanzaAcknowledgements {
        var callback: (() -> Unit)? = null
        var sent = 0
        var requests = 0
        var unregistered = 0
        var acknowledge = false
        var emitFailure: Exception? = null
        override fun register(id: String, acknowledged: () -> Unit) { callback = acknowledged }
        override fun unregister(id: String) { callback = null; unregistered++ }
        override fun emit(message: Message) { sent++; emitFailure?.let { throw it } }
        override fun request() { requests++; if (acknowledge) callback?.invoke() }
    }
    private fun message() = StanzaBuilder.buildMessage("stable").to("bob@example.org").build()

    @Test fun writerEnqueueWithoutServerAcknowledgementIsUncertain() {
        val backend = Backend()
        assertThrows(UncertainSendException::class.java) { ConfirmedStanzaSender(backend, 0).send(message()) }
        assertEquals(1, backend.sent); assertEquals(1, backend.requests); assertEquals(1, backend.unregistered)
    }
    @Test fun successfulServerAcknowledgementAllowsSentAndReleasesListener() {
        val backend = Backend().apply { acknowledge = true }
        ConfirmedStanzaSender(backend, 0).send(message())
        assertEquals(1, backend.sent); assertEquals(1, backend.requests); assertEquals(1, backend.unregistered)
        assertNull(backend.callback)
    }
    @Test fun unsupportedStreamManagementDoesNotFalselyClaimSent() {
        val backend = Backend(false)
        assertThrows(UncertainSendException::class.java) { ConfirmedStanzaSender(backend, 0).send(message()) }
        assertEquals(1, backend.sent); assertEquals(0, backend.requests); assertEquals(0, backend.unregistered)
    }
    @Test fun socketFailureAfterEmissionPreservesUncertaintyAndReleasesListener() {
        val backend = Backend().apply { emitFailure = IOException("Injected socket failure") }
        assertThrows(UncertainSendException::class.java) { ConfirmedStanzaSender(backend, 0).send(message()) }
        assertEquals(1, backend.sent); assertEquals(1, backend.unregistered)
    }
}
