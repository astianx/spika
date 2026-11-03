package org.astianx.midori.one.chat

import org.json.JSONArray
import org.json.JSONObject

/** Account-scoped snapshot codec. No network, UI mutation or cryptographic ownership. */
internal class ChatHistoryRepository(
    private val records: ChatRecords,
    private val hasPreview: (String) -> Boolean = { false }
) {
    private val inbound = ChatInboundJournal(records)

    fun accept(account: String, message: ChatMessage) = inbound.accept(account, message)

    fun load(account: String): ChatUiState = synchronized(records) {
        val saved = records.get("history/$account")?.let(::decode) ?: ChatUiState()
        val merged = saved.messages.toMutableList()
        inbound.pending(account).forEach { message ->
            val options = saved.conversationOptions[message.peer] ?: ConversationOptions()
            if (message.peer !in saved.blocked && message.mediaKey !in options.deletedIds &&
                (options.deletedBefore == 0L || message.timestamp > options.deletedBefore)) {
                val index = merged.indexOfFirst { it.peer == message.peer && it.id == message.id &&
                    it.outgoing == message.outgoing && (it.sender.isBlank() || it.sender == message.sender) }
                if (index < 0) merged += message
                else merged[index] = merged[index].reconcileIncoming(message)
            }
        }
        saved.copy(messages = merged.sortedBy { it.timestamp })
    }

    fun save(s: ChatUiState) {
        if (s.account.isBlank()) return
        val json = JSONObject().put("readReceipts", s.readReceipts).put("blocked", JSONArray(s.blocked.toList()))
            .put("requests", JSONArray(s.requests.toList())).put("profile", s.profile.toJson())
            .put("publishedProfile", s.publishedProfile?.toJson())
            .put("contactProfiles", JSONObject(s.contactProfiles.mapValues { it.value.toJson() }))
            .put("contacts", JSONArray(s.contacts.map { JSONObject().put("address", it.address).put("name", it.name).put("group", it.group) }))
            .put("drafts", JSONObject(s.drafts))
            .put("mediaFiles", JSONObject(s.mediaFiles))
            .put("conversationOptions", JSONObject(s.conversationOptions.mapValues { (_, o) -> JSONObject()
                .put("pinned", o.pinned).put("muted", o.muted).put("hidden", o.hidden).put("deletedBefore", o.deletedBefore)
                .put("deletedIds", JSONArray(o.deletedIds.toList())).put("appearance", o.appearance?.toJson())
                .put("alias", o.alias).put("phone", o.phone) }))
            .put("groups", JSONArray(s.groups.map { JSONObject().put("address", it.address).put("name", it.name)
                .put("members", JSONArray(it.members.toList())).put("owner", it.owner).put("joined", it.joined) }))
            .put("invitations", JSONArray(s.invitations.map { JSONObject().put("room", it.room).put("inviter", it.inviter) }))
            .put("messages", JSONArray(s.messages.map { m -> JSONObject().put("id", m.id).put("peer", m.peer)
                .put("body", ChatPayload.encode(m)).put("sender", m.sender).put("outgoing", m.outgoing).put("timestamp", m.timestamp).put("delivery", m.delivery.name)
                .put("protection", m.protection.name).put("read", m.read).put("markable", m.markable) }))
        synchronized(records) {
            records.durableBatch {
                records.put("history/${s.account}", json.toString())
                inbound.incorporated(s.account, s.messages)
                val ignored = inbound.pending(s.account).filter { message ->
                    val options = s.conversationOptions[message.peer] ?: ConversationOptions()
                    message.peer in s.blocked || message.mediaKey in options.deletedIds ||
                        options.deletedBefore > 0 && message.timestamp <= options.deletedBefore
                }
                inbound.incorporated(s.account, ignored)
                val prepared = PreparedSendStore(records, s.account)
                s.messages.filter { it.outgoing && it.delivery in setOf(MessageDelivery.SENT, MessageDelivery.DELIVERED, MessageDelivery.READ) }
                    .forEach { prepared.confirmed(it.peer, it.id) }
            }
        }
    }
    private fun decode(value: String): ChatUiState {
        val json = JSONObject(value)
        val contacts = json.getJSONArray("contacts")
        val messages = json.getJSONArray("messages")
        val blocked = json.getJSONArray("blocked")
        val groups = json.optJSONArray("groups") ?: JSONArray()
        val invitations = json.optJSONArray("invitations") ?: JSONArray()
        val drafts = json.optJSONObject("drafts") ?: JSONObject()
        val requests = json.optJSONArray("requests") ?: JSONArray()
        val options = json.optJSONObject("conversationOptions") ?: JSONObject()
        val files = json.optJSONObject("mediaFiles") ?: JSONObject()
        val profiles = json.optJSONObject("contactProfiles") ?: JSONObject()
        // A send interrupted by a crash keeps its message (now UNCERTAIN); the same text must not also wait in the composer.
        val interrupted = (0 until messages.length()).map { messages.getJSONObject(it) }
            .filter { it.getString("delivery") in setOf("PENDING", "PREPARING", "SENDING") && it.getBoolean("outgoing") }
            .associate { it.getString("peer") to ChatPayload.decode(ChatMessage("", "", it.getString("body"), true, 0)).body }
        return ChatUiState(readReceipts = json.optBoolean("readReceipts", true),
            profile = json.optJSONObject("profile")?.let(ChatProfile::fromJson) ?: ChatProfile(),
            publishedProfile = json.optJSONObject("publishedProfile")?.let(PublishedChatProfile::fromJson),
            contactProfiles = profiles.keys().asSequence().take(200).associateWith { PublishedChatProfile.fromJson(profiles.getJSONObject(it)) },
            mediaFiles = files.keys().asSequence().associateWith { files.getString(it) }.filterValues { hasPreview(it) },
            conversationOptions = options.keys().asSequence().associateWith { key -> options.getJSONObject(key).let {
                val ids = it.optJSONArray("deletedIds") ?: JSONArray()
                ConversationOptions(it.optBoolean("pinned"), it.optBoolean("muted"), it.optBoolean("hidden"), it.optLong("deletedBefore"),
                    (0 until ids.length()).map { index -> ids.getString(index) }.toSet(),
                    it.optJSONObject("appearance")?.let { value -> runCatching { ChatAppearance.fromJson(value) }.getOrNull() }, it.optString("alias"), it.optString("phone")) } },
            drafts = drafts.keys().asSequence().associateWith { drafts.getString(it) }
                .filter { (peer, text) -> interrupted[peer] != text },
            groups = (0 until groups.length()).map { groups.getJSONObject(it).let { g ->
                val members = g.getJSONArray("members")
                ChatGroup(g.getString("address"), g.getString("name"), (0 until members.length()).map { members.getString(it) }.toSet(),
                    g.optBoolean("owner"), false) } },
            invitations = (0 until invitations.length()).map { invitations.getJSONObject(it).let { i -> GroupInvitation(i.getString("room"), i.getString("inviter")) } },
            requests = (0 until requests.length()).map { requests.getString(it) }.toSet(),
            blocked = (0 until blocked.length()).map { blocked.getString(it) }.toSet(),
            contacts = (0 until contacts.length()).map { contacts.getJSONObject(it).let { c -> ChatContact(c.getString("address"), c.getString("name"), group = c.optBoolean("group")) } },
            messages = (0 until messages.length()).map { messages.getJSONObject(it).let { m ->
                val status = MessageDelivery.valueOf(m.getString("delivery"))
                ChatPayload.decode(ChatMessage(m.getString("id"), m.getString("peer"), m.getString("body"), m.getBoolean("outgoing"), m.getLong("timestamp"),
                    when (status) {
                        MessageDelivery.SENDING -> MessageDelivery.UNCERTAIN
                        MessageDelivery.PREPARING -> MessageDelivery.PENDING
                        else -> status
                    },
                    MessageProtection.valueOf(m.getString("protection")), m.getBoolean("read"), m.optBoolean("markable"), sender = m.optString("sender")))
            } })
    }
}
