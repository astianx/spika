package org.astianx.midori.one.chat

import java.security.MessageDigest
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.util.PacketParserUtils
import org.json.JSONObject

/** Ciphertext is stored in the same durable batch as every ratchet advance.
 * Re-emission reuses the exact stanza and ID, after validating the complete recipient snapshot.
 */
internal class PreparedSendStore(private val records: ChatRecords, private val account: String) {
    private fun key(peer: String, id: String): String {
        val identity = org.json.JSONArray(listOf(peer, id)).toString().toByteArray()
        return "prepared/$account/" + MessageDigest.getInstance("SHA-256").digest(identity).joinToString("") { "%02x".format(it) }
    }
    fun prepare(peer: String, id: String, snapshot: String, create: () -> Message): Message {
        records.get(key(peer, id))?.let {
            val saved = JSONObject(it)
            if (saved.getString("recipients") != snapshot) throw ChatException(org.astianx.midori.one.UiText(org.astianx.midori.one.R.string.chat_group_changed))
            val message = PacketParserUtils.parseMessage(PacketParserUtils.getParserFor(saved.getString("xml")),
                org.jivesoftware.smack.packet.XmlEnvironment.EMPTY, org.jxmpp.JxmppContext.getDefaultContext())
            check(message.stanzaId == id && message.to.asBareJid().toString() == peer && message.body == null &&
                message.hasExtension("encrypted", SmackMessenger.OMEMO_NAMESPACE))
            return message
        }
        return records.durableBatch {
            create().also { message ->
                check(message.stanzaId == id && message.body == null && message.hasExtension("encrypted", SmackMessenger.OMEMO_NAMESPACE))
                records.put(key(peer, id), JSONObject().put("recipients", snapshot)
                    .put("xml", message.toXML().toString()).toString())
            }
        }
    }
    fun confirmed(peer: String, id: String) {
        val name = key(peer, id)
        if (records.get(name) != null) records.put(name, null)
    }
}

/** Order-independent, fingerprint-bound snapshot; trust is rechecked on every attempt. */
internal fun recipientIdentitySnapshot(members: Set<String>, devices: List<ChatDevice>): String {
    if (devices.any { !it.trusted }) throw org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException(
        devices.filter { !it.trusted }.map { org.jivesoftware.smackx.omemo.internal.OmemoDevice(org.jxmpp.jid.impl.JidCreate.bareFrom(it.address), it.id) })
    return JSONObject().put("members", org.json.JSONArray(members.sorted()))
        .put("devices", org.json.JSONArray(devices.sortedWith(compareBy({ it.address }, { it.id })).map {
            org.json.JSONArray(listOf(it.address, it.id.toString(), it.fingerprint))
        })).toString()
}
