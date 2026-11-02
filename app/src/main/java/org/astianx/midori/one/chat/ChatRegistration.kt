package org.astianx.midori.one.chat

import org.astianx.midori.one.BuildConfig
import org.astianx.midori.one.R
import org.astianx.midori.one.UiText
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.iqregister.AccountManager
import org.jxmpp.jid.impl.JidCreate

/** Explicit one-shot registration. No retries: a lost response may already have created the account. */
internal object ChatRegistration {
    fun create(account: ChatAccount) {
        val jid = JidCreate.entityBareFrom(account.address)
        val config = XMPPTCPConnectionConfiguration.builder().setXmppDomain(jid.domain.toString())
            .setHost(account.host).setPort(account.port)
            .setSecurityMode(ConnectionConfiguration.SecurityMode.required)
            .setSendPresence(false)
        ChatTls.applyLocalPin(config, account, BuildConfig.CHAT_DEV_CERT_SHA256)
        val connection = XMPPTCPConnection(config.build())
        try {
            connection.connect()
            check(connection.isSecureConnection)
            val manager = AccountManager.getInstance(connection)
            if (!manager.supportsAccountCreation() || manager.accountAttributes.any { it !in setOf("username", "password") }) {
                throw ChatException(UiText(R.string.profile_registration_unavailable))
            }
            manager.createAccount(jid.localpart, account.password)
        } catch (e: XMPPException.XMPPErrorException) {
            throw ChatException(UiText(if (e.stanzaError.condition == StanzaError.Condition.conflict)
                R.string.profile_username_taken else R.string.profile_registration_unavailable))
        } finally { connection.disconnect() }
    }
}

/** Messaging service operated for the pilot. Only a username is asked; the password never leaves the encrypted vault. */
internal data class ChatPilot(val host: String, val domain: String, val port: Int) {
    fun address(username: String): String {
        require(username.matches(USERNAME))
        return chatAddress("$username@$domain")
    }
    companion object {
        val USERNAME = Regex("[a-z0-9][a-z0-9._-]{2,31}")
        fun fromBuild(): ChatPilot? = if (BuildConfig.CHAT_PILOT_HOST.isBlank() || BuildConfig.CHAT_PILOT_DOMAIN.isBlank()) null
            else ChatPilot(BuildConfig.CHAT_PILOT_HOST, BuildConfig.CHAT_PILOT_DOMAIN, BuildConfig.CHAT_PILOT_PORT)
        fun newPassword(): String {
            val bytes = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
