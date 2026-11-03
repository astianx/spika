package org.astianx.midori.one.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.astianx.midori.one.R
import org.astianx.midori.one.UiText
import org.jivesoftware.smack.android.AndroidSmackInitializer
import org.jivesoftware.smack.sasl.SASLErrorException
import org.jivesoftware.smackx.omemo.exceptions.UndecidedOmemoIdentityException
import org.json.JSONObject

class ChatViewModel internal constructor(
    private val records: ChatRecords,
    private val transportFactory: () -> ChatTransport,
    private val notifyMessage: (ChatMessage, Int) -> Unit = { _, _ -> },
    private val clearNotification: (String?) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val media: ChatMedia? = null,
    private val artwork: ChatArtwork? = null,
    private val registerAccount: (ChatAccount) -> Unit = ChatRegistration::create,
    initialForeground: Boolean = true
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatUiState(restoringSession = true))
    val state: StateFlow<ChatUiState> = mutableState
    private val mutex = Mutex()
    private val history = ChatHistoryRepository(records) { media?.hasPreview(it) == true }
    private val transfers = ChatTransferCoordinator(viewModelScope, io, mutex) { e ->
        mutableState.update { it.copy(error = errorText(e), busy = false, mediaProgress = null) }
    }
    private val sends = ChatSendCoordinator(viewModelScope, io, mutex) { e ->
        mutableState.update { it.copy(error = errorText(e), busy = false) }
    }
    private val captureLeases = mutableSetOf<String>()
    private data class TransferSession(val account: String, val generation: Long, val client: ChatTransport, val peer: String)
    private data class UploadWork(val session: TransferSession, val original: MediaSelection)
    private data class Uploaded(val attachment: ChatAttachment, val selection: MediaSelection, val preview: String?)

    private fun transferSession(peer: String): TransferSession {
        requireOnline()
        val s = state.value
        if (peer in s.blocked) throw ChatException(UiText(R.string.chat_contact_blocked))
        if (s.groups.any { it.address == peer && !it.joined }) throw ChatException(UiText(R.string.chat_group_not_joined))
        return TransferSession(s.account, generation, requireNotNull(transport), peer)
    }
    private fun current(session: TransferSession) = generation == session.generation &&
        state.value.account == session.account && transport === session.client
    private fun progress(session: TransferSession, value: Int) {
        if (current(session)) mutableState.update { it.copy(mediaProgress = value) }
    }
    private fun finishTransfer(work: UploadWork, result: Uploaded?) {
        captureLeases.remove(work.original.uri)
        result?.selection?.takeIf { it != work.original }?.let(::releaseSelectedCapture)
        result?.preview?.takeIf { it !in state.value.mediaFiles.values }?.let { media?.removePreview(it) }
        releaseSelectedCapture(work.original)
        if (current(work.session)) mutableState.update { it.copy(busy = false, mediaProgress = null) }
    }
    private var transport: ChatTransport? = null
    private var credentials: ChatAccount? = null
    @Volatile private var generation = 0L
    private var draftSave: kotlinx.coroutines.Job? = null
    @Volatile private var foreground = initialForeground
    @Volatile private var autoRetryBlocked = false
    private var retryJob: kotlinx.coroutines.Job? = null
    private var retryAttempt = 0
    private val awaitingVerification = mutableMapOf<String, MutableSet<String>>()

    init {
        task {
            try {
                mutableState.update { it.copy(backgroundConnection = records.get("settings/background") != "false",
                    wallpaper = records.get("settings/wallpaper") == "true") }
                val appearance = records.get("settings/appearance")?.let { runCatching { ChatAppearance.fromJson(JSONObject(it)) }.getOrNull() }
                    ?: ChatAppearance(wallpaper = if (state.value.wallpaper) ChatWallpaperStyle.BOTANICAL else ChatWallpaperStyle.PLAIN)
                mutableState.update { it.copy(appearance = appearance, wallpaper = appearance.wallpaper != ChatWallpaperStyle.PLAIN) }
                if (records.get("session") == null) pendingRegistrationRecord()?.let { saved ->
                    mutableState.update { it.copy(pendingRegistration = saved.address) }
                }
                records.get("session")?.let { saved ->
                    val json = JSONObject(saved)
                    val account = ChatAccount(json.getString("address"), json.getString("password"),
                        json.getString("host"), json.getInt("port"))
                    if (foreground || state.value.backgroundConnection) connectAccount(account)
                    else {
                        credentials = account
                        restoreAccountState(account, ChatConnection.DISCONNECTED)
                    }
                }
            } finally { mutableState.update { it.copy(restoringSession = false) } }
        }
    }

    private fun task(block: suspend () -> Unit) = viewModelScope.launch(io) {
        mutex.withLock {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(error = errorText(e), busy = false) } }
        }
    }

    fun connect(address: String, password: String, host: String, port: String) = task {
        val normalized = runCatching { chatAddress(address) }.getOrElse { throw ChatException(UiText(R.string.chat_invalid_address)) }
        val number = port.toIntOrNull() ?: throw ChatException(UiText(R.string.chat_invalid_server))
        if (number !in 1..65535 || password.isEmpty() || host.isBlank() || host.contains('/') || host.contains(':')) {
            throw ChatException(UiText(R.string.chat_invalid_server))
        }
        connectAccount(ChatAccount(normalized, password, host.trim(), number))
    }

    fun register(address: String, password: String, host: String, port: String) = task {
        require(state.value.account.isBlank())
        val normalized = runCatching { chatAddress(address) }.getOrElse { throw ChatException(UiText(R.string.chat_invalid_address)) }
        val number = port.toIntOrNull()
        if (number == null || number !in 1..65535 || host.isBlank() || host.contains('/') || host.contains(':') || password.length < 12) {
            throw ChatException(UiText(R.string.profile_registration_fields))
        }
        mutableState.update { it.copy(busy = true, error = null) }
        val account = ChatAccount(normalized, password, host.trim(), number)
        try { createAccountResumably(account) }
        finally { mutableState.update { it.copy(busy = false) } }
    }

    internal fun registerPilot(username: String, pilot: ChatPilot? = ChatPilot.fromBuild(),
        password: String = ChatPilot.newPassword()) = task {
        require(state.value.account.isBlank())
        val service = pilot ?: throw ChatException(UiText(R.string.profile_registration_unavailable))
        val name = username.trim().lowercase()
        if (!name.matches(ChatPilot.USERNAME)) throw ChatException(UiText(R.string.chat_pilot_username_invalid))
        mutableState.update { it.copy(busy = true, error = null) }
        try { createAccountResumably(ChatAccount(service.address(name), password, service.host, service.port)) }
        finally { mutableState.update { it.copy(busy = false) } }
    }

    /**
     * Writes the intent before the request. If the response is lost or the name already exists, the same
     * credentials are tried once: when they authenticate, the account is ours and nothing else is created.
     */
    private fun createAccountResumably(account: ChatAccount) {
        savePendingRegistration(account)
        try {
            registerAccount(account)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            try {
                connectAccount(account)
                clearPendingRegistration()
                return
            } catch (_: Exception) {
                discardUnconfirmedAccount()
                // A definite refusal leaves nothing to resume; an unknown outcome keeps the intent for a retry.
                if (failure is ChatException) clearPendingRegistration()
                else mutableState.update { it.copy(pendingRegistration = account.address) }
                throw failure
            }
        }
        connectAccount(account)
        clearPendingRegistration()
    }

    /** Retries sign-in with the credentials saved before a registration whose outcome is unknown. */
    fun resumeRegistration() = task {
        val pending = pendingRegistrationRecord() ?: return@task
        mutableState.update { it.copy(busy = true, error = null) }
        try {
            connectAccount(pending)
            clearPendingRegistration()
        } catch (e: SASLErrorException) {
            discardUnconfirmedAccount()
            clearPendingRegistration()
            throw ChatException(UiText(R.string.profile_registration_not_created))
        } catch (e: Exception) {
            discardUnconfirmedAccount()
            mutableState.update { it.copy(pendingRegistration = pending.address) }
            throw e
        } finally { mutableState.update { it.copy(busy = false) } }
    }

    fun discardPendingRegistration() = task { clearPendingRegistration() }

    private fun pendingRegistrationRecord(): ChatAccount? = records.get("pending-registration")?.let {
        runCatching {
            val json = JSONObject(it)
            ChatAccount(json.getString("address"), json.getString("password"), json.getString("host"), json.getInt("port"))
        }.getOrNull()
    }
    private fun savePendingRegistration(account: ChatAccount) {
        records.put("pending-registration", JSONObject().put("address", account.address).put("password", account.password)
            .put("host", account.host).put("port", account.port).toString())
        mutableState.update { it.copy(pendingRegistration = account.address) }
    }
    private fun clearPendingRegistration() {
        records.put("pending-registration", null)
        mutableState.update { it.copy(pendingRegistration = null) }
    }
    /** A connection attempt for an account that could not be confirmed must not leave a signed-in-looking state. */
    private fun discardUnconfirmedAccount() {
        ++generation
        retryJob?.cancel()
        retryJob = null
        autoRetryBlocked = false
        transport?.disconnect()
        transport = null
        credentials = null
        mutableState.update { ChatUiState(backgroundConnection = it.backgroundConnection, wallpaper = it.wallpaper,
            appearance = it.appearance, pendingRegistration = it.pendingRegistration) }
    }

    fun saveProfile(name: String, bio: String) = task {
        val profile = state.value.profile.copy(name = name.trim(), bio = bio.trim())
        profile.validate()
        updateProfile(profile)
    }
    fun setProfilePhoto(uri: android.net.Uri) = task {
        require(state.value.account.isNotBlank())
        val file = artwork?.import(uri) ?: throw ChatException(UiText(R.string.style_image_error))
        try { updateProfile(state.value.profile.copy(photo = file)) }
        catch (e: Exception) { artwork.file(file).delete(); throw e }
    }
    fun removeProfilePhoto() = task { updateProfile(state.value.profile.copy(photo = null)) }
    private fun updateProfile(profile: ChatProfile) {
        require(state.value.account.isNotBlank())
        val before = state.value
        mutableState.update { it.copy(profile = profile, error = null) }
        try { saveHistory() } catch (e: Exception) { mutableState.value = before; throw e }
        before.profile.photo?.takeIf { it != profile.photo }?.let { artwork?.file(it)?.delete() }
    }

    fun publishProfile() = task {
        requireOnline()
        mutableState.update { it.copy(profileLoading = true, error = null) }
        try {
            val editable = state.value.profile
            val photo = editable.photo?.let { publicProfilePhoto(requireNotNull(artwork).file(it)) }
            val value = PublishedChatProfile(editable.name, editable.bio, photo, photo?.let { "image/jpeg" })
            transport!!.publishProfile(value)
            mutableState.update { it.copy(publishedProfile = value) }
            saveHistory()
        } catch (_: Exception) { throw ChatException(UiText(R.string.profile_publish_error)) }
        finally { mutableState.update { it.copy(profileLoading = false) } }
    }
    fun withdrawProfile() = task {
        requireOnline()
        mutableState.update { it.copy(profileLoading = true, error = null) }
        try {
            transport!!.publishProfile(PublishedChatProfile())
            mutableState.update { it.copy(publishedProfile = null) }
            saveHistory()
        } catch (_: Exception) { throw ChatException(UiText(R.string.profile_publish_error)) }
        finally { mutableState.update { it.copy(profileLoading = false) } }
    }
    fun refreshPublishedProfile() = task {
        requireOnline()
        mutableState.update { it.copy(profileLoading = true, error = null) }
        try {
            val value = transport!!.loadProfile(state.value.account)
            mutableState.update { it.copy(publishedProfile = value.takeUnless { profile -> profile.name.isBlank() && profile.bio.isBlank() && profile.photoBase64 == null }) }
            saveHistory()
        } catch (_: Exception) { throw ChatException(UiText(R.string.profile_load_error)) }
        finally { mutableState.update { it.copy(profileLoading = false) } }
    }
    fun loadContactProfile(peer: String) = task {
        val address = chatAddress(peer)
        if (address in state.value.blocked) return@task
        requireOnline()
        mutableState.update { it.copy(profileLoading = true, error = null) }
        try {
            val value = transport!!.loadProfile(address)
            mutableState.update { it.copy(contactProfiles = cacheContactProfile(it.contactProfiles, address, value)) }
            saveHistory()
        } catch (_: Exception) { throw ChatException(UiText(R.string.profile_load_error)) }
        finally { mutableState.update { it.copy(profileLoading = false) } }
    }
    fun prepareContact(input: String) {
        val address = runCatching { parseChatContactLink(input) }.getOrNull()
        mutableState.update { it.copy(pendingContactAddress = address, error = if (address == null) UiText(R.string.chat_invalid_address) else null) }
    }
    fun dismissContactRequest() { mutableState.update { it.copy(pendingContactAddress = null) } }
    fun saveContactDetails(peer: String, name: String, phone: String) = task {
        val details = validatedContactDetails(name, phone)
        changeConversation(chatAddress(peer)) { it.copy(alias = details.first, phone = details.second) }
    }
    fun addContactDetails(input: String, name: String, phone: String) = task {
        val address = runCatching { parseChatContactLink(input) }.getOrElse { throw ChatException(UiText(R.string.chat_invalid_address)) }
        val details = validatedContactDetails(name, phone)
        requireOnline()
        transport!!.addContact(address)
        val before = state.value
        val options = before.conversationOptions[address] ?: ConversationOptions()
        mutableState.update { it.copy(contacts = (it.contacts + ChatContact(address)).distinctBy { contact -> contact.address },
            conversationOptions = it.conversationOptions + (address to options.copy(alias = details.first, phone = details.second)),
            error = null) }
        try { saveHistory() } catch (e: Exception) { mutableState.value = before; throw e }
    }

    private fun connectAccount(account: ChatAccount) {
        retryJob?.cancel()
        retryJob = null
        val token = ++generation
        transport?.disconnect()
        credentials = account
        restoreAccountState(account, ChatConnection.CONNECTING)
        val client = transportFactory()
        transport = client
        try {
            client.connect(account, object : ChatEvents {
                private fun current() = generation == token
                override fun connection(status: ChatConnection) {
                    if (!current()) return
                    updateConnection(status)
                    if (status in setOf(ChatConnection.ERROR, ChatConnection.DISCONNECTED)) scheduleRetry()
                }
                override fun connectionFailed(error: Exception) {
                    if (!current()) return
                    autoRetryBlocked = !canRetryConnection(error)
                    mutableState.update { it.copy(error = errorText(error)) }
                    updateConnection(ChatConnection.ERROR)
                    if (!autoRetryBlocked) scheduleRetry()
                }
                override fun contacts(contacts: List<ChatContact>) { task {
                    if (!current()) return@task
                    mutableState.update { s -> s.copy(contacts = (contacts + s.contacts.filter { old -> contacts.none { it.address == old.address } }).distinctBy { it.address }) }
                    saveHistory()
                } }
                override fun message(message: ChatMessage, historical: Boolean) {
                    // Synchronous durable handoff. Do not make Smack wait for this ViewModel's
                    // state mutex: connect/archive sync may itself be holding that mutex.
                    if (!current()) throw java.io.IOException("Obsolete messaging session")
                    history.accept(account.address, message)
                    task {
                    if (!current() || message.peer in state.value.blocked) return@task
                    val before = state.value
                    val options = before.conversationOptions[message.peer] ?: ConversationOptions()
                    if (message.mediaKey in options.deletedIds || (options.deletedBefore > 0 && message.timestamp <= options.deletedBefore)) return@task
                    val index = before.messages.indexOfFirst { it.id == message.id && it.peer == message.peer && it.outgoing == message.outgoing &&
                        (it.sender.isBlank() || message.sender.isBlank() || it.sender == message.sender) }
                    if (index >= 0) {
                        // An archived or carbon copy of our own message proves it left this device.
                        val known = before.messages[index]
                        val reconciled = known.reconcileIncoming(message)
                        if (reconciled != known) {
                            mutableState.update { s -> s.copy(messages = s.messages.map { if (it === known) reconciled else it }) }
                        }
                        saveHistory()
                        return@task
                    }
                    val isOpen = foreground && before.selectedPeer == message.peer
                    val received = message.copy(read = message.outgoing || isOpen)
                    mutableState.update { s -> s.copy(messages = (s.messages + received).sortedBy { it.timestamp },
                        conversationOptions = s.conversationOptions + (message.peer to options.copy(hidden = false)),
                        contacts = if (s.contacts.any { it.address == message.peer }) s.contacts else s.contacts + ChatContact(message.peer)) }
                    saveHistory()
                    if (!historical && !message.outgoing && !isOpen && !options.muted && message.protection == MessageProtection.ENCRYPTED) {
                        notifyMessage(message, state.value.messages.count {
                            it.peer == message.peer && !it.read && !it.outgoing && it.protection == MessageProtection.ENCRYPTED
                        })
                    }
                    if (!historical && isOpen && before.readReceipts && message.markable && message.protection == MessageProtection.ENCRYPTED) {
                        client.markRead(message.peer, message.id)
                    }
                } }
                override fun receipt(peer: String, id: String, status: MessageDelivery) { task {
                    if (!current()) return@task
                    mutableState.update { s -> s.copy(messages = s.messages.map { it.withReceipt(peer, id, status) }) }
                    saveHistory()
                } }
                override fun fingerprint(value: String) { if (current()) mutableState.update { it.copy(ownFingerprint = value) } }
                override fun blocked(addresses: Set<String>) { task {
                    if (current()) { mutableState.update { it.copy(blocked = addresses) }; saveHistory() }
                } }
                override fun subscriptionRequested(peer: String) { task {
                    if (current() && peer !in state.value.blocked) {
                        mutableState.update { it.copy(requests = it.requests + peer) }; saveHistory()
                    }
                } }
                override fun group(group: ChatGroup) { task {
                    if (!current()) return@task
                    mutableState.update { st -> st.copy(groups = st.groups.filterNot { it.address == group.address } + group,
                        contacts = st.contacts.filterNot { it.address == group.address } + ChatContact(group.address, group.name, group.joined, true)) }
                    saveHistory()
                } }
                override fun invited(invitation: GroupInvitation) { task {
                    if (!current() || invitation.inviter in state.value.blocked) return@task
                    mutableState.update { it.copy(invitations = (it.invitations + invitation).distinctBy { v -> v.room }) }
                    saveHistory()
                } }
                override fun failure(error: UiText) { if (current()) mutableState.update { it.copy(error = error) } }
            })
            // Save only after TLS, authentication and OMEMO initialization succeed. Failures preserve old credentials.
            records.put("session", JSONObject().put("address", account.address).put("password", account.password)
                .put("host", account.host).put("port", account.port).toString())
            retryAttempt = 0
            autoRetryBlocked = false
            resumePendingSends()
        } catch (e: Exception) {
            autoRetryBlocked = !canRetryConnection(e)
            updateConnection(ChatConnection.ERROR)
            if (!autoRetryBlocked) scheduleRetry()
            throw e
        }
    }

    private fun updateConnection(status: ChatConnection) {
        mutableState.update { it.copy(connection = status,
            contacts = if (status == ChatConnection.ONLINE) it.contacts else it.contacts.map { c -> c.copy(online = false) }) }
    }

    private fun canRetryConnection(error: Exception) = !ChatTls.isIdentityFailure(error) &&
        error !is SASLErrorException && error !is ChatException &&
        (error is java.io.IOException || error is org.jivesoftware.smack.SmackException.NoResponseException ||
            error is org.jivesoftware.smack.SmackException.ConnectionException ||
            error is org.jivesoftware.smack.SmackException.NotConnectedException)

    private fun scheduleRetry() {
        if (autoRetryBlocked || retryJob?.isActive == true) return
        val token = generation
        val wait = (5_000L shl retryAttempt.coerceAtMost(4)).coerceAtMost(60_000L)
        retryAttempt++
        retryJob = viewModelScope.launch {
            delay(wait)
            task {
                if (generation == token && !autoRetryBlocked && canConnectInBackground() &&
                    state.value.connection in setOf(ChatConnection.ERROR, ChatConnection.DISCONNECTED)) {
                    credentials?.let(::connectAccount)
                }
            }
        }
    }

    private fun restoreAccountState(account: ChatAccount, connection: ChatConnection) {
        val restored = history.load(account.address)
        mutableState.value = restored.copy(account = account.address, connection = connection,
            restoringSession = state.value.restoringSession,
            backgroundConnection = state.value.backgroundConnection, connectionServiceRunning = state.value.connectionServiceRunning,
            wallpaper = state.value.wallpaper, appearance = state.value.appearance,
            selectedPeer = state.value.selectedPeer.takeIf { state.value.account == account.address }, error = null)
    }

    private fun canConnectInBackground() = foreground ||
        (state.value.backgroundConnection && state.value.connectionServiceRunning)

    fun retry() = task { autoRetryBlocked = false; retryAttempt = 0; credentials?.let(::connectAccount) }
    fun networkAvailable() = task {
        if (!autoRetryBlocked && canConnectInBackground() &&
            state.value.connection in setOf(ChatConnection.ERROR, ChatConnection.DISCONNECTED)) credentials?.let(::connectAccount)
    }
    fun networkLost() = task {
        if (credentials == null || autoRetryBlocked) return@task
        ++generation
        retryJob?.cancel()
        retryJob = null
        transport?.disconnect()
        updateConnection(ChatConnection.DISCONNECTED)
        scheduleRetry()
    }
    fun setConnectionServiceRunning(running: Boolean) {
        mutableState.update { it.copy(connectionServiceRunning = running) }
        if (running) networkAvailable()
    }
    fun setBackgroundConnection(enabled: Boolean) = task {
        records.put("settings/background", enabled.toString())
        mutableState.update { it.copy(backgroundConnection = enabled) }
    }
    fun setWallpaper(enabled: Boolean) = setAppearance(state.value.appearance.copy(
        wallpaper = if (enabled) ChatWallpaperStyle.BOTANICAL else ChatWallpaperStyle.PLAIN))

    fun setAppearance(value: ChatAppearance, peer: String? = null) = task { saveAppearance(value, peer) }
    private fun saveAppearance(value: ChatAppearance, peer: String?) {
        ChatAppearance.fromJson(value.toJson()) // Validate every entry point, including imported themes.
        val previousPhoto = state.value.appearanceFor(peer).photo
        if (peer == null) {
            records.put("settings/appearance", value.toJson().toString())
            mutableState.update { it.copy(appearance = value, wallpaper = value.wallpaper != ChatWallpaperStyle.PLAIN) }
        } else changeConversation(peer) { it.copy(appearance = value) }
        previousPhoto?.takeIf { it != value.photo }?.let(::deleteUnusedArtwork)
    }
    fun resetConversationAppearance(peer: String) = task {
        val previous = state.value.conversationOptions[peer]?.appearance?.photo
        changeConversation(peer) { it.copy(appearance = null) }
        previous?.let(::deleteUnusedArtwork)
    }
    fun setConversationAlias(peer: String, name: String) = task {
        require(name.length <= 60 && name.none { it.isISOControl() })
        changeConversation(peer) { it.copy(alias = name.trim()) }
    }
    fun importWallpaper(uri: android.net.Uri, peer: String? = null) = task {
        val file = artwork?.import(uri) ?: throw ChatException(UiText(R.string.style_image_error))
        try { saveAppearance(state.value.appearanceFor(peer).copy(wallpaper = ChatWallpaperStyle.PHOTO, photo = file), peer) }
        catch (e: Exception) { artwork.file(file).delete(); throw e }
    }
    private fun deleteUnusedArtwork(name: String) {
        // An override in another account may still reference a formerly global photo.
        // A read failure preserves the file rather than risking data loss.
        runCatching {
            val keys = records.keys("history/") + "settings/appearance"
            if (keys.none { records.get(it)?.contains(name) == true }) artwork?.file(name)?.delete()
        }
    }
    fun clearError() { mutableState.update { it.copy(error = null) } }
    fun open(peer: String) = task {
        val address = runCatching { resolveChatHandle(peer, state.value.account) }.getOrElse { throw ChatException(UiText(R.string.chat_invalid_address)) }
        var discarded: MediaSelection? = null
        mutableState.update {
            discarded = it.pendingMedia.takeIf { _ -> it.selectedPeer != address }
            it.copy(selectedPeer = address, devices = emptyList(), error = null,
            replyingTo = it.replyingTo.takeIf { quoted -> quoted?.peer == address },
            pendingMedia = if (it.selectedPeer == address) it.pendingMedia else null,
            conversationOptions = it.conversationOptions + (address to (it.conversationOptions[address] ?: ConversationOptions()).copy(hidden = false)),
            contacts = if (it.contacts.any { c -> c.address == address }) it.contacts else it.contacts + ChatContact(address)) }
        discarded?.let(::releaseSelectedCapture)
        markVisibleRead(address)
        saveHistory()
    }
    fun closeConversation() {
        var discarded: MediaSelection? = null
        mutableState.update {
            discarded = it.pendingMedia
            it.copy(selectedPeer = null, pendingMedia = null, devices = emptyList(), error = null, replyingTo = null)
        }
        discarded?.let { selection -> task { releaseSelectedCapture(selection) } }
    }
    fun pinConversation(peer: String, pinned: Boolean) = task {
        changeConversation(peer) { it.copy(pinned = pinned) }
    }
    fun muteConversation(peer: String, muted: Boolean) = task {
        changeConversation(peer) { it.copy(muted = muted) }
        if (muted) clearNotification(peer)
    }
    private fun changeConversation(peer: String, change: (ConversationOptions) -> ConversationOptions) {
        val before = state.value
        if (before.contacts.none { it.address == peer }) return
        mutableState.update { it.copy(conversationOptions = it.conversationOptions + (peer to change(it.conversationOptions[peer] ?: ConversationOptions()))) }
        try { saveHistory() } catch (e: Exception) { mutableState.value = before; throw e }
    }
    /** Local deletion only. Retain the cutoff so MAM and delayed group history cannot restore erased messages. */
    fun deleteConversation(peer: String) = task {
        val before = state.value
        if (before.contacts.none { it.address == peer }) return@task
        val removed = before.messages.filter { it.peer == peer }.map { it.mediaKey }.toSet()
        val previous = before.conversationOptions[peer] ?: ConversationOptions()
        // Remote timestamps must not push the cutoff into the future and suppress later messages.
        val options = previous.copy(hidden = true, pinned = false, deletedBefore = System.currentTimeMillis(),
            deletedIds = previous.deletedIds + removed)
        mutableState.update { it.copy(messages = it.messages.filterNot { m -> m.peer == peer }, drafts = it.drafts - peer,
            conversationOptions = it.conversationOptions + (peer to options), mediaFiles = it.mediaFiles - removed,
            selectedPeer = it.selectedPeer.takeUnless { selected -> selected == peer }) }
        try { saveHistory() } catch (e: Exception) { mutableState.value = before; throw e }
        removed.mapNotNull { before.mediaFiles[it] }.forEach { media?.removePreview(it) }
        clearNotification(peer)
    }
    fun addContact(address: String) = task {
        val peer = runCatching { chatAddress(address) }.getOrElse { throw ChatException(UiText(R.string.chat_invalid_address)) }
        requireOnline()
        transport!!.addContact(peer)
        mutableState.update { it.copy(contacts = (it.contacts + ChatContact(peer)).distinctBy { c -> c.address }) }
        saveHistory()
    }

    fun updateDraft(text: String) {
        val peer = state.value.selectedPeer ?: return
        if (text.length > 8_000) return
        mutableState.update { it.copy(drafts = it.drafts + (peer to text)) }
        draftSave?.cancel()
        draftSave = viewModelScope.launch { delay(500); task { saveHistory() }.join() }
    }
    fun send(body: String) = task {
        if (body.isBlank()) return@task
        val current = state.value
        val quoted = current.replyingTo?.takeIf { it.peer == current.selectedPeer }?.let { replyReference(it, current.account) }
        sendContent(body, reply = quoted)
    }
    /** 1:1 only: a quote in a group would need every member to resolve the original. */
    fun startReply(message: ChatMessage) {
        val s = state.value
        if (message.peer != s.selectedPeer || message.protection != MessageProtection.ENCRYPTED ||
            s.groups.any { it.address == message.peer } || message !in s.messages) return
        mutableState.update { it.copy(replyingTo = message) }
    }
    fun cancelReply() { mutableState.update { it.copy(replyingTo = null) } }
    private fun replyReference(message: ChatMessage, account: String): ReplyReference = ReplyReference(message.id,
        message.sender.ifBlank { if (message.outgoing) account else message.peer },
        message.body.ifBlank { message.attachment?.name ?: message.sharedContact ?: "" }
            .filter { !it.isISOControl() || it == '\n' }.take(ChatPayload.REPLY_PREVIEW_CHARS))
    private fun sendContent(body: String, attachment: ChatAttachment? = null, contact: String? = null, location: String? = null,
        clearDraft: Boolean = true, targetPeer: String? = null, reply: ReplyReference? = null) {
        val s = state.value
        val peer = targetPeer ?: s.selectedPeer ?: return
        if (body.length > 8_000) throw ChatException(UiText(R.string.chat_message_too_long))
        if (s.account.isBlank()) throw ChatException(UiText(R.string.chat_offline_error))
        if (peer in s.blocked) throw ChatException(UiText(R.string.chat_contact_blocked))
        if (s.connection == ChatConnection.ONLINE && s.groups.any { it.address == peer && !it.joined })
            throw ChatException(UiText(R.string.chat_group_not_joined))
        val message = ChatMessage(UUID.randomUUID().toString(), peer, body, true, System.currentTimeMillis(),
            MessageDelivery.PENDING, read = true, sender = s.account, attachment = attachment, sharedContact = contact, location = location,
            replyTo = reply)
        mutableState.update { it.copy(messages = it.messages + message, busy = false, error = null,
            drafts = if (clearDraft && it.drafts[peer] == s.drafts[peer]) it.drafts - peer else it.drafts,
            pendingMedia = if (clearDraft) null else it.pendingMedia,
            replyingTo = if (reply != null) null else it.replyingTo) }
        try { saveHistory() } catch (e: Exception) {
            mutableState.value = s.copy(messages = s.messages + message.copy(delivery = MessageDelivery.FAILED))
            throw e
        }
        queueSend(message)
    }

    private fun queueSend(message: ChatMessage) {
        val token = generation
        val account = state.value.account
        val client = transport ?: return
        if (state.value.connection != ChatConnection.ONLINE) return
        sends.submit("$token/${message.mediaKey}", prepare = {
            val s = state.value
            val original = s.messages.find { it.mediaKey == message.mediaKey }
            if (token != generation || account != s.account || client !== transport || s.connection != ChatConnection.ONLINE ||
                original == null || original.delivery !in setOf(MessageDelivery.PENDING, MessageDelivery.FAILED, MessageDelivery.UNCERTAIN)) null
            else {
                if (original.peer in s.blocked) throw ChatException(UiText(R.string.chat_contact_blocked))
                if (s.groups.any { it.address == original.peer && !it.joined }) throw ChatException(UiText(R.string.chat_group_not_joined))
                mutableState.update { it.copy(error = null, messages = it.messages.map { m ->
                    if (m.mediaKey == original.mediaKey) m.copy(delivery = MessageDelivery.PREPARING) else m }) }
                try { saveHistory() } catch (e: Exception) { mutableState.value = s; throw e }
                ChatSendAttempt(original, client, token)
            }
        }, emitting = { attempt ->
            if (attempt.generation != generation || attempt.client !== transport || account != state.value.account)
                throw java.io.IOException("Obsolete send attempt")
            val before = state.value
            mutableState.update { it.copy(messages = it.messages.map { m ->
                if (m.mediaKey == attempt.message.mediaKey && m.delivery == MessageDelivery.PREPARING) m.copy(delivery = MessageDelivery.SENDING) else m }) }
            try { saveHistory() } catch (e: Exception) { mutableState.value = before; throw e }
        }, complete = { attempt, result ->
            if (attempt.generation == generation && attempt.client === transport && account == state.value.account) {
                val before = state.value
                val failure = result.exceptionOrNull() as? Exception
                failure?.let { noteSendFailure(attempt.message.peer, attempt.message.id, it) }
                mutableState.update { it.copy(messages = it.messages.map { m ->
                    if (m.mediaKey != attempt.message.mediaKey || m.delivery in setOf(MessageDelivery.SENT, MessageDelivery.DELIVERED, MessageDelivery.READ)) m
                    else if (failure == null) m.withReceipt(m.peer, m.id, MessageDelivery.SENT)
                    else m.copy(delivery = if (attempt.message.delivery == MessageDelivery.UNCERTAIN ||
                        failure is UncertainSendException || failure is CancellationException) MessageDelivery.UNCERTAIN else MessageDelivery.FAILED)
                }, error = failure?.let(::errorText), verificationPeer = if (failure is UndecidedOmemoIdentityException) attempt.message.peer else it.verificationPeer) }
                try { saveHistory() } catch (e: Exception) { mutableState.value = before; throw e }
            }
        })
    }
    private fun resumePendingSends() {
        state.value.messages.filter { it.outgoing && it.delivery == MessageDelivery.PENDING }.forEach(::queueSend)
    }
    /** Remembers which messages wait for a device approval so approving can send them without retyping. */
    private fun noteSendFailure(peer: String, id: String, e: Exception) {
        if (e is UndecidedOmemoIdentityException) awaitingVerification.getOrPut(peer) { linkedSetOf() }.add(id)
    }
    fun retryMessage(message: ChatMessage) = task {
        val s = state.value
        val original = s.messages.find { it.id == message.id && it.peer == message.peer && it.outgoing } ?: return@task
        if (original.peer == s.selectedPeer) resend(original)
    }
    private fun resend(original: ChatMessage) {
        if (original.delivery !in setOf(MessageDelivery.FAILED, MessageDelivery.UNCERTAIN)) return
        requireOnline()
        queueSend(original)
    }
    fun selectMedia(uri: android.net.Uri, videoNote: Boolean = false, asDocument: Boolean = false,
        expectedPeer: String? = state.value.selectedPeer, expectedAccount: String = state.value.account): kotlinx.coroutines.Job {
        // Arguments bind picker/camera results to the conversation and account that launched them.
        return task {
            val service = requireNotNull(media)
            val current = state.value
            if (expectedPeer == null || current.selectedPeer != expectedPeer || current.account != expectedAccount) {
                releaseSelectedCapture(MediaSelection(uri.toString(), "", "application/octet-stream", -1))
                return@task
            }
            // File/provider IO must never run inside StateFlow's retryable CAS callback.
            val selection = service.describe(uri, videoNote, asDocument)
            var installed = false
            var replaced: MediaSelection? = null
            mutableState.update {
                installed = it.selectedPeer == expectedPeer && it.account == expectedAccount
                replaced = if (installed) it.pendingMedia else null
                if (installed) it.copy(pendingMedia = selection, error = null) else it
            }
            if (!installed) releaseSelectedCapture(selection)
            else replaced?.takeIf { it.uri != selection.uri }?.let(::releaseSelectedCapture)
        }
    }
    fun dismissMedia() {
        val selected = state.value.pendingMedia
        mutableState.update { it.copy(pendingMedia = if (it.pendingMedia == selected) null else it.pendingMedia) }
        // An active transfer holds a lease until its completion/cancellation cleanup.
        if (selected != null) task { releaseSelectedCapture(selected) }
    }
    private fun releaseSelectedCapture(selection: MediaSelection) {
        val service = media ?: return
        val path = service.ownCapturePath(selection) ?: return
        if (selection.uri !in captureLeases && state.value.pendingMedia?.uri != selection.uri && path !in state.value.mediaFiles.values) {
            service.removeOwnCapture(selection)
        }
    }
    internal fun sendSelectedMedia(caption: String, image: android.graphics.Bitmap? = null, edits: PhotoEdits = PhotoEdits(),
        quality: PhotoQuality = PhotoQuality.STANDARD): kotlinx.coroutines.Job {
        val initial = state.value
        val original = initial.pendingMedia
        val peer = initial.selectedPeer
        val token = generation
        return transfers.start(
            prepare = {
                if (original == null || peer == null || generation != token || state.value.pendingMedia != original) null
                else UploadWork(transferSession(peer), original).also {
                    captureLeases.add(original.uri)
                    mutableState.update { it.copy(busy = true, mediaProgress = 0, error = null) }
                }
            },
            transfer = { work ->
                val service = requireNotNull(media)
                var edited: MediaSelection? = null
                try {
                    val selection = if (image != null) service.exportImage(work.original, edits, quality).let {
                        service.describe(android.net.Uri.fromFile(it)).also { value -> edited = value }
                    } else work.original
                    val attachment = service.upload(selection, work.session.client) { progress(work.session, it) }
                    val preview = runCatching { service.previewCopy(selection).absolutePath }.getOrNull()
                        ?: service.ownCapturePath(selection)
                    Uploaded(attachment, selection, preview)
                } catch (e: Exception) {
                    edited?.let { service.removeOwnCapture(it) }
                    throw e
                }
            },
            commit = { work, result ->
                if (current(work.session)) {
                    sendContent(caption, attachment = result.attachment, clearDraft = !work.original.videoNote, targetPeer = work.session.peer)
                    state.value.messages.lastOrNull { it.outgoing && it.peer == work.session.peer && it.attachment == result.attachment }?.let { message ->
                        mutableState.update { it.copy(pendingMedia = if (it.pendingMedia == work.original) null else it.pendingMedia,
                            mediaFiles = if (result.preview == null) it.mediaFiles else it.mediaFiles + (message.mediaKey to result.preview)) }
                        saveHistory()
                    }
                }
            },
            isCurrent = { current(it.session) }, cleanup = ::finishTransfer
        )
    }
    /** The inline recorder keeps its private file on upload failure and never consumes the text draft. */
    internal fun sendVoiceRecording(peer: String, source: java.io.File, onComplete: (Boolean) -> Unit): kotlinx.coroutines.Job {
        val expectedAccount = state.value.account
        val token = generation
        val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
        return transfers.start(
            prepare = {
                if (expectedAccount.isBlank() || state.value.account != expectedAccount || generation != token) null
                else {
                    val session = transferSession(peer)
                    val selection = requireNotNull(media).describe(android.net.Uri.fromFile(source))
                    require(selection.mime == "audio/mp4")
                    captureLeases.add(selection.uri)
                    mutableState.update { it.copy(busy = true, mediaProgress = 0, error = null) }
                    UploadWork(session, selection)
                }
            },
            transfer = { work ->
                val service = requireNotNull(media)
                val attachment = service.upload(work.original, work.session.client) { progress(work.session, it) }
                Uploaded(attachment, work.original, runCatching { service.previewCopy(work.original).absolutePath }.getOrNull())
            },
            commit = { work, result ->
                if (current(work.session)) {
                    try { sendContent("", attachment = result.attachment, clearDraft = false, targetPeer = peer) }
                    finally { accepted.set(state.value.messages.any { it.peer == peer && it.outgoing && it.attachment == result.attachment }) }
                    state.value.messages.lastOrNull { it.outgoing && it.peer == peer && it.attachment == result.attachment }?.let { message ->
                        result.preview?.let { path ->
                            mutableState.update { it.copy(mediaFiles = it.mediaFiles + (message.mediaKey to path)) }
                            saveHistory()
                        }
                    }
                }
            },
            isCurrent = { current(it.session) }, cleanup = { work, result ->
                // The recorder owns the source until its completion callback accepts it.
                captureLeases.remove(work.original.uri)
                result?.preview?.takeIf { it !in state.value.mediaFiles.values }?.let { media?.removePreview(it) }
                if (current(work.session)) mutableState.update { it.copy(busy = false, mediaProgress = null) }
            }
        ).also { job -> job.invokeOnCompletion { onComplete(accepted.get()) } }
    }
    fun downloadMedia(message: ChatMessage): kotlinx.coroutines.Job {
        val token = generation
        return transfers.start(
            prepare = {
                if (generation != token || message.attachment == null || message.peer != state.value.selectedPeer || message !in state.value.messages) null
                else transferSession(message.peer).also { mutableState.update { s -> s.copy(busy = true, mediaProgress = 0, error = null) } }
            },
            transfer = { session -> requireNotNull(media).download(requireNotNull(message.attachment), session.client) { progress(session, it) }.absolutePath },
            commit = { session, path ->
                if (current(session) && state.value.messages.any { it.mediaKey == message.mediaKey }) {
                    mutableState.update { it.copy(mediaFiles = it.mediaFiles + (message.mediaKey to path)) }
                    saveHistory()
                }
            },
            isCurrent = ::current, cleanup = { session, path ->
                path?.takeIf { it !in state.value.mediaFiles.values }?.let { media?.removePreview(it) }
                if (current(session)) mutableState.update { it.copy(busy = false, mediaProgress = null) }
            }
        )
    }
    fun shareContact(address: String) = state.value.selectedPeer.let { peer ->
        task { if (peer != null) sendContent("", contact = chatAddress(address), clearDraft = false, targetPeer = peer) }
    }
    fun shareLocation(value: String) = state.value.selectedPeer.let { peer ->
        task { if (peer != null) { ChatPayload.validateLocation(value); sendContent("", location = value, clearDraft = false, targetPeer = peer) } }
    }
    fun showError(text: UiText) { mutableState.update { it.copy(error = text) } }
    fun createGroup(name: String, members: Set<String>) = task {
        requireOnline()
        mutableState.update { it.copy(busy = true, error = null) }
        val group = transport!!.createGroup(name.trim(), members.map(::chatAddress).toSet())
        mutableState.update { it.copy(selectedPeer = group.address, busy = false) }
    }
    fun acceptInvitation(invitation: GroupInvitation) = task {
        requireOnline()
        transport!!.joinGroup(invitation.room)
        mutableState.update { it.copy(invitations = it.invitations.filterNot { v -> v.room == invitation.room }, selectedPeer = invitation.room) }
        saveHistory()
    }
    fun declineInvitation(invitation: GroupInvitation) = task {
        mutableState.update { it.copy(invitations = it.invitations - invitation) }; saveHistory()
    }
    fun refreshGroup() = task { requireOnline(); state.value.selectedPeer?.let { transport!!.groupInfo(it) } }
    fun rejoinGroup() = task { requireOnline(); state.value.selectedPeer?.let { transport!!.joinGroup(it) } }
    fun inviteGroupMember(address: String) = task { requireOnline(); state.value.selectedPeer?.let { transport!!.inviteToGroup(it, chatAddress(address)) } }
    fun removeGroupMember(address: String) = task { requireOnline(); state.value.selectedPeer?.let { transport!!.removeGroupMember(it, chatAddress(address)) } }
    fun deleteGroup() = task { requireOnline(); state.value.selectedPeer?.let { transport!!.deleteGroup(it) } }
    fun leaveGroup() = task { requireOnline(); state.value.selectedPeer?.let { transport!!.leaveGroup(it) } }

    fun loadDevices() = task {
        requireOnline()
        val peer = state.value.selectedPeer ?: state.value.account
        mutableState.update { it.copy(busy = true, error = null) }
        val devices = transport!!.devices(peer)
        mutableState.update { it.copy(devices = devices, busy = false, verificationPeer = verificationAfter(it, peer, devices)) }
    }
    /** Looks for unapproved devices before the first send, so the explanation appears before a blocked message. */
    fun checkPeerTrust() = task {
        val s = state.value
        val peer = s.selectedPeer ?: return@task
        if (s.connection != ChatConnection.ONLINE || s.groups.any { it.address == peer }) return@task
        val devices = runCatching { transport!!.devices(peer) }.getOrNull() ?: return@task
        mutableState.update { it.copy(verificationPeer = verificationAfter(it, peer, devices)) }
    }
    private fun verificationAfter(s: ChatUiState, peer: String, devices: List<ChatDevice>): String? {
        if (peer != s.selectedPeer) return s.verificationPeer
        val pending = devices.any { it.address == peer && !it.trusted }
        return if (pending) peer else s.verificationPeer.takeUnless { it == peer }
    }
    fun trust(device: ChatDevice, trusted: Boolean) = task {
        requireOnline()
        transport!!.trust(device, trusted)
        mutableState.update { s ->
            val devices = s.devices.map { if (it == device) it.copy(trusted = trusted) else it }
            s.copy(devices = devices, error = null, verificationPeer = verificationAfter(s, device.address, devices))
        }
        // Approving is the explicit decision; only messages that were blocked by that approval are sent.
        if (trusted && state.value.verificationPeer == null) {
            awaitingVerification.remove(device.address).orEmpty().forEach { id ->
                state.value.messages.find { it.id == id && it.peer == device.address && it.outgoing }?.let { resend(it) }
            }
        }
    }
    fun blockSelected() = task {
        requireOnline()
        val s = state.value
        val peer = s.selectedPeer ?: return@task
        transport!!.block(peer, peer !in s.blocked)
    }
    fun setReadReceipts(enabled: Boolean) = task {
        mutableState.update { it.copy(readReceipts = enabled) }
        saveHistory()
    }
    fun answerSubscription(peer: String, approve: Boolean) = task {
        requireOnline()
        transport!!.answerSubscription(peer, approve)
        mutableState.update { it.copy(requests = it.requests - peer) }
        saveHistory()
    }
    private fun requireOnline() {
        if (state.value.connection != ChatConnection.ONLINE) throw ChatException(UiText(R.string.chat_offline_error))
    }
    fun setForeground(value: Boolean) = task {
        foreground = value
        if (!value) saveHistory()
        transport?.setActive(value)
        if (!value && !state.value.backgroundConnection) {
            ++generation
            retryJob?.cancel()
            transport?.disconnect()
            updateConnection(ChatConnection.DISCONNECTED)
        }
        if (value) {
            state.value.selectedPeer?.let { markVisibleRead(it); saveHistory() }
            if (!autoRetryBlocked && state.value.connection in setOf(ChatConnection.DISCONNECTED, ChatConnection.ERROR)) credentials?.let(::connectAccount)
        }
    }
    private fun markVisibleRead(peer: String) {
        val s = state.value
        val last = s.messages.lastOrNull { it.peer == peer && !it.outgoing && it.markable && it.protection == MessageProtection.ENCRYPTED }
        mutableState.update { it.copy(messages = it.messages.map { m -> if (m.peer == peer) m.copy(read = true) else m }) }
        clearNotification(peer)
        if (s.readReceipts && s.connection == ChatConnection.ONLINE && last != null) transport?.markRead(peer, last.id)
    }
    fun logout() = task {
        ++generation
        transfers.cancel()
        sends.cancel()
        retryJob?.cancel()
        autoRetryBlocked = false
        transport?.disconnect()
        transport = null
        credentials = null
        awaitingVerification.clear()
        media?.clearPreviews()
        records.put("session", null)
        mutableState.value = ChatUiState(backgroundConnection = state.value.backgroundConnection, wallpaper = state.value.wallpaper, appearance = state.value.appearance)
        clearNotification(null)
    }

    private fun saveHistory() = history.save(state.value)

    override fun onCleared() {
        transfers.cancel()
        sends.cancel()
        ++generation
        // Socket close can block; never run it on the UI thread.
        val current = transport
        Thread { current?.disconnect() }.start()
    }
    private fun errorText(e: Exception): UiText = when {
        e is ChatException -> e.text
        e is UndecidedOmemoIdentityException -> UiText(R.string.chat_verify_first)
        ChatTls.isIdentityFailure(e) -> UiText(R.string.chat_tls_error)
        e is SASLErrorException -> UiText(R.string.chat_login_error)
        e is org.jivesoftware.smack.XMPPException.XMPPErrorException && e.stanzaError.condition ==
            org.jivesoftware.smack.packet.StanzaError.Condition.subscription_required -> UiText(R.string.chat_subscription_required)
        else -> UiText(R.string.chat_connection_error)
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
            AndroidSmackInitializer.initialize(context)
            val vault = ChatVault.forApp(context)
            val notifications = ChatNotifications(context)
            return ChatViewModel(vault, { SmackMessenger(vault) }, notifications::show, notifications::clear,
                media = ChatMedia(context.applicationContext), artwork = ChatArtwork(context.applicationContext), initialForeground = false) as T
        }
    }
}
