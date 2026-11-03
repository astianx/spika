package org.astianx.midori.one.chat

import java.security.MessageDigest
import org.json.JSONObject

/** Durable handoff from Smack's callbacks to the asynchronous UI projection.
 * A receipt or archive checkpoint may only follow [accept]'s successful return.
 * Entries survive process death until the history snapshot has incorporated them.
 */
internal class ChatInboundJournal(private val records: ChatRecords) {
    private fun prefix(account: String) = "inbound/$account/"
    private fun key(account: String, message: ChatMessage): String {
        val identity = listOf(message.peer, message.sender.ifBlank { if (message.outgoing) account else message.peer },
            message.outgoing.toString(), message.id)
        val bytes = org.json.JSONArray(identity).toString().toByteArray(Charsets.UTF_8)
        return prefix(account) + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun accept(account: String, message: ChatMessage) {
        require(account.isNotBlank() && message.id.isNotBlank())
        synchronized(records) {
            val name = key(account, message)
            // Redelivery must preserve readable content already durably received.
            val previous = records.get(name)?.let { ChatMessageJson.decode(JSONObject(it)) }
            if (previous == null || previous.protection != MessageProtection.ENCRYPTED && message.protection == MessageProtection.ENCRYPTED)
                records.put(name, ChatMessageJson.encode(message).toString())
        }
    }

    fun pending(account: String): List<ChatMessage> = synchronized(records) {
        records.keys(prefix(account)).map { ChatMessageJson.decode(JSONObject(requireNotNull(records.get(it)))) }
    }

    /** Caller commits this with the snapshot. Deleting a journal alone is never an acknowledgement. */
    fun incorporated(account: String, messages: List<ChatMessage>) {
        messages.forEach { message ->
            val name = key(account, message)
            val pending = records.get(name)?.let { ChatMessageJson.decode(JSONObject(it)) }
            if (pending != null && pending.protection == message.protection && ChatPayload.encode(pending) == ChatPayload.encode(message))
                records.put(name, null)
        }
    }
}

internal object ChatMessageJson {
    fun encode(m: ChatMessage): JSONObject = JSONObject().put("id", m.id).put("peer", m.peer)
        .put("body", ChatPayload.encode(m)).put("sender", m.sender).put("outgoing", m.outgoing)
        .put("timestamp", m.timestamp).put("delivery", m.delivery.name).put("protection", m.protection.name)
        .put("read", m.read).put("markable", m.markable)

    fun decode(m: JSONObject): ChatMessage = ChatPayload.decode(ChatMessage(m.getString("id"), m.getString("peer"),
        m.getString("body"), m.getBoolean("outgoing"), m.getLong("timestamp"),
        MessageDelivery.valueOf(m.getString("delivery")), MessageProtection.valueOf(m.getString("protection")),
        m.getBoolean("read"), m.optBoolean("markable"), sender = m.optString("sender")))
}
