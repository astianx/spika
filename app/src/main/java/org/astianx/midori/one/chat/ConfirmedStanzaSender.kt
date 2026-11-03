package org.astianx.midori.one.chat

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.tcp.XMPPTCPConnection

internal interface StanzaAcknowledgements {
    val available: Boolean
    fun register(id: String, acknowledged: () -> Unit)
    fun unregister(id: String)
    fun emit(message: Message)
    fun request()
}

/** sendStanza only queues work in Smack's packet writer. SENT requires server handling
 * confirmation, not just successful enqueueing. Without it the prepared stanza stays
 * uncertain; a later receipt or archive/room echo can reconcile it.
 */
internal class ConfirmedStanzaSender(private val backend: StanzaAcknowledgements, private val timeoutMs: Long = 15_000) {
    fun send(message: Message) {
        val acknowledged = CountDownLatch(1)
        val supported = backend.available
        var registered = false
        try {
            if (supported) { backend.register(message.stanzaId) { acknowledged.countDown() }; registered = true }
            backend.emit(message)
            if (!supported) throw IOException("XMPP send acknowledgement unavailable")
            backend.request()
            if (!acknowledged.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IOException("XMPP send acknowledgement timed out")
        } catch (e: Exception) { throw UncertainSendException(e) }
        finally { if (registered) backend.unregister(message.stanzaId) }
    }
}

internal fun sendConfirmedStanza(connection: XMPPConnection, message: Message) {
    check(message.body == null && message.hasExtension("encrypted", SmackMessenger.OMEMO_NAMESPACE))
    val tcp = connection as? XMPPTCPConnection
    ConfirmedStanzaSender(object : StanzaAcknowledgements {
        override val available get() = tcp?.isSmEnabled == true
        override fun register(id: String, acknowledged: () -> Unit) { requireNotNull(tcp).addStanzaIdAcknowledgedListener(id) { acknowledged() } }
        override fun unregister(id: String) { tcp?.removeStanzaIdAcknowledgedListener(id) }
        override fun emit(message: Message) { connection.sendStanza(message) }
        override fun request() { requireNotNull(tcp).requestSmAcknowledgement() }
    }).send(message)
}
