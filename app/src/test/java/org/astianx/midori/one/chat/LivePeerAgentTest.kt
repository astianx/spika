/* Copyright 2026 Astian. Licensed under the Apache License, Version 2.0. */
package org.astianx.midori.one.chat

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.security.Security
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.astianx.midori.one.UiText
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jivesoftware.smack.java7.Java7SmackInitializer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in, file-driven laboratory peer for device acceptance. It is a genuine ejabberd/OMEMO client in its own JVM
 * with its own encrypted state; the driver script sends JSON commands and reads decoded events. It never touches
 * the app's vault. Commands: addContact, answer, trust, send, loadProfile, download, status, stop.
 */
class LivePeerAgentTest {
    @Test fun runUntilStopped() {
        val filename = System.getProperty("midori.peerAgent.fixture")
        assumeTrue("Explicit peer agent fixture is required", filename != null)
        val fixture = File(requireNotNull(filename)).absoluteFile
        val config = JSONObject(fixture.readText())
        val directory = requireNotNull(fixture.parentFile)
        val account = config.getString("account")
        require(account.matches(Regex("midori-acc-[a-z0-9-]+@localhost")))
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        Java7SmackInitializer().initialize()
        val commands = File(directory, "commands").apply { mkdirs() }
        val results = File(directory, "results").apply { mkdirs() }
        val eventLog = File(directory, "events.jsonl")
        fun event(json: JSONObject) = synchronized(eventLog) {
            eventLog.appendText(json.put("at", System.currentTimeMillis()).toString() + "\n")
        }
        val messages = CopyOnWriteArrayList<ChatMessage>()
        val requests = ConcurrentHashMap.newKeySet<String>()
        val online = ConcurrentHashMap.newKeySet<String>()
        val fingerprint = java.util.concurrent.atomic.AtomicReference("")
        val events = object : ChatEvents {
            override fun connection(status: ChatConnection) = event(JSONObject().put("type", "connection").put("status", status.name))
            override fun contacts(contacts: List<ChatContact>) { online.clear(); online.addAll(contacts.filter { it.online }.map { it.address }) }
            override fun message(message: ChatMessage, historical: Boolean) {
                messages.add(message)
                event(JSONObject().put("type", "message").put("id", message.id).put("peer", message.peer)
                    .put("outgoing", message.outgoing).put("body", message.body).put("historical", historical)
                    .put("protection", message.protection.name)
                    .put("reply", message.replyTo?.let { JSONObject().put("id", it.id).put("from", it.sender).put("text", it.preview) })
                    .put("file", message.attachment?.let { JSONObject().put("name", it.name).put("mime", it.mime).put("size", it.size) }))
            }
            override fun receipt(peer: String, id: String, status: MessageDelivery) =
                event(JSONObject().put("type", "receipt").put("peer", peer).put("id", id).put("status", status.name))
            override fun fingerprint(value: String) { fingerprint.set(value) }
            override fun blocked(addresses: Set<String>) = Unit
            override fun subscriptionRequested(peer: String) {
                requests.add(peer); event(JSONObject().put("type", "subscription").put("peer", peer))
            }
            override fun failure(error: UiText) = event(JSONObject().put("type", "failure"))
        }
        val records = AgentRecords(File(directory, "peer-state.enc"), config.getString("password"))
        val peer = SmackMessenger(records, config.getString("pin"))
        try {
            peer.connect(ChatAccount(account, config.getString("password"), "127.0.0.1"), events)
            event(JSONObject().put("type", "ready").put("account", account).put("fingerprint", fingerprint.get()))
            val done = HashSet<String>()
            while (!File(directory, "stop").exists()) {
                (commands.listFiles()?.sortedBy { it.name } ?: emptyList()).filter { it.name !in done }.forEach { file ->
                    done.add(file.name)
                    val command = JSONObject(file.readText())
                    val result = try { execute(peer, command, messages, requests, online, config.getString("pin")).put("ok", true) }
                    catch (e: Throwable) { JSONObject().put("ok", false).put("error", e.javaClass.simpleName + ": " + (e.message ?: "")) }
                    write(File(results, file.name), result.toString())
                }
                Thread.sleep(150)
            }
        } finally { peer.disconnect() }
    }

    private fun execute(peer: SmackMessenger, c: JSONObject, messages: List<ChatMessage>, requests: Set<String>,
        online: Set<String>, pin: String): JSONObject = when (c.getString("op")) {
        "addContact" -> JSONObject().also { peer.addContact(c.getString("peer")) }
        "answer" -> JSONObject().also { peer.answerSubscription(c.getString("peer"), c.optBoolean("approve", true)) }
        "trust" -> {
            val address = c.getString("peer")
            val devices = peer.devices(address).filter { it.address == address }
            devices.forEach { peer.trust(it, true) }
            JSONObject().put("trusted", JSONArray(devices.map { JSONObject().put("id", it.id).put("fingerprint", it.fingerprint) }))
        }
        "send" -> {
            val id = c.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }
            val reply = c.optJSONObject("reply")?.let { ReplyReference(it.getString("id"), it.getString("from"), it.getString("text")) }
            val to = c.getString("peer")
            peer.send(to, ChatPayload.encode(ChatMessage(id, to, c.getString("body"), true, System.currentTimeMillis(), replyTo = reply)), id)
            JSONObject().put("id", id)
        }
        "loadProfile" -> {
            val profile = peer.loadProfile(c.getString("peer"))
            JSONObject().put("name", profile.name).put("bio", profile.bio).put("photo", profile.photoBase64 != null)
        }
        "download" -> {
            val message = messages.last { it.id == c.getString("id") && !it.outgoing }
            val file = requireNotNull(message.attachment)
            val bytes = downloadLaboratoryMedia(file, pin)
            try { JSONObject().put("size", bytes.size).put("name", file.name).put("mime", file.mime)
                .put("sha256", ChatFileCrypto.hash(bytes)).put("declaredSha256", file.sha256) } finally { bytes.fill(0) }
        }
        "status" -> JSONObject().put("requests", JSONArray(requests.toList())).put("online", JSONArray(online.toList()))
            .put("messages", messages.size)
        else -> error("Unknown command")
    }

    private fun downloadLaboratoryMedia(file: ChatAttachment, pin: String): ByteArray {
        val url = java.net.URL(file.url)
        require(url.protocol == "https" && url.host == "localhost" && url.port == 5443 && url.userInfo == null && url.ref == null)
        val trust = object : javax.net.ssl.X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, type: String) = error("Client certificate not expected")
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, type: String) {
                val certificate = chain.first(); certificate.checkValidity()
                require(ChatFileCrypto.hash(certificate.encoded).equals(pin, ignoreCase = true))
            }
        }
        val connection = url.openConnection() as javax.net.ssl.HttpsURLConnection
        connection.sslSocketFactory = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }.socketFactory
        connection.connectTimeout = 15_000; connection.readTimeout = 60_000
        connection.instanceFollowRedirects = false
        try {
            check(connection.responseCode == 200)
            val encrypted = connection.inputStream.use { it.readNBytes((file.size + 17).toInt()) }
            return ChatFileCrypto.decrypt(encrypted, file)
        } finally { connection.disconnect() }
    }

    private fun write(file: File, text: String) {
        val temporary = Files.createTempFile(requireNotNull(file.parentFile).toPath(), ".agent-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            java.io.FileOutputStream(temporary.toFile()).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    /** Laboratory persistence only: AES-GCM under a password-derived key, no Android store. */
    private class AgentRecords(private val file: File, password: String) : ChatRecords {
        private val random = SecureRandom()
        private val base64 = Base64.getEncoder()
        private val decode = Base64.getDecoder()
        private val existing = if (file.exists()) JSONObject(file.readText()) else null
        private val salt = existing?.getString("salt")?.let(decode::decode) ?: ByteArray(16).also(random::nextBytes)
        private val key = PBEKeySpec(password.toCharArray(), salt, 120_000, 256).let { spec ->
            try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
            finally { spec.clearPassword() }
        }
        private val values = mutableMapOf<String, String>()
        init {
            existing?.let { envelope ->
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode.decode(envelope.getString("iv"))))
                val json = JSONObject(String(cipher.doFinal(decode.decode(envelope.getString("ciphertext"))), Charsets.UTF_8))
                json.keys().forEach { values[it] = json.getString(it) }
            }
        }
        @Synchronized override fun get(key: String) = values[key]
        @Synchronized override fun keys(prefix: String) = values.keys.filter { it.startsWith(prefix) }.toSet()
        @Synchronized override fun put(key: String, value: String?) {
            val next = values.toMutableMap()
            if (value == null) next.remove(key) else next[key] = value
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, this.key, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(JSONObject(next as Map<*, *>).toString().toByteArray(Charsets.UTF_8))
            val envelope = JSONObject().put("salt", base64.encodeToString(salt)).put("iv", base64.encodeToString(iv))
                .put("ciphertext", base64.encodeToString(ciphertext)).toString()
            val temporary = Files.createTempFile(requireNotNull(file.parentFile).toPath(), ".peer-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            try {
                java.io.FileOutputStream(temporary.toFile()).use { out -> out.write(envelope.toByteArray(Charsets.UTF_8)); out.fd.sync() }
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                values.clear(); values.putAll(next)
            } finally { Files.deleteIfExists(temporary) }
        }
    }
}
