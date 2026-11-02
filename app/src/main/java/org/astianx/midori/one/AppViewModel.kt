package org.astianx.midori.one

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A launch request never adds a contact or sends a message. Chat owns validation and consent. */
data class AppLaunch(
    val contactLink: String? = null,
    val peer: String? = null,
    val connectionSettings: Boolean = false
)

data class AppUiState(
    val currentScreen: Screen = Screen.Welcome,
    val introductionPage: Int = 0,
    val hasEnteredApp: Boolean = false,
    val feature: AppFeature = AppFeature.DESKTOP,
    val detailReturnScreen: Screen = Screen.Chats,
    val messagingReturnScreen: Screen = Screen.Chats,
    val introductionReturnScreen: Screen = Screen.Chats,
    val toastMessage: UiText? = null,
    val toastIsSuccess: Boolean = false,
    val pendingLaunch: AppLaunch? = null
)

/** App navigation and transient notices; no account credentials or financial runtime. */
class AppViewModel internal constructor(private val entryStore: AppEntryStore = MemoryAppEntryStore()) : ViewModel() {
    private val entered = entryStore.hasEntered()
    private val mutable = MutableStateFlow(AppUiState(
        currentScreen = if (entered) Screen.Chats else Screen.Welcome,
        hasEnteredApp = entered
    ))
    val state = mutable.asStateFlow()
    private var toastJob: Job? = null
    private var initialLaunchHandled = false

    fun navigate(screen: Screen) {
        mutable.update { current -> current.copy(
            currentScreen = screen,
            messagingReturnScreen = if (screen == Screen.Messaging && current.currentScreen != screen)
                current.currentScreen else current.messagingReturnScreen,
            detailReturnScreen = if (screen == Screen.Help && current.currentScreen != screen)
                current.currentScreen else current.detailReturnScreen,
            introductionReturnScreen = if (screen == Screen.Welcome && current.currentScreen != screen)
                current.currentScreen else current.introductionReturnScreen
        ) }
    }

    fun enterApp() {
        entryStore.markEntered()
        mutable.update { it.copy(currentScreen = Screen.Chats, hasEnteredApp = true) }
    }

    fun updateIntroductionPage(page: Int) {
        if (page in IntroSlide.entries.indices) mutable.update { it.copy(introductionPage = page) }
    }

    fun openFeature(feature: AppFeature) {
        mutable.update { it.copy(feature = feature, detailReturnScreen = it.currentScreen, currentScreen = Screen.Feature) }
    }

    fun acceptLaunch(launch: AppLaunch, initial: Boolean = false) {
        if (initial && initialLaunchHandled) return
        initialLaunchHandled = true
        mutable.update { it.copy(pendingLaunch = launch) }
    }
    fun consumeLaunch(launch: AppLaunch) {
        mutable.update { if (it.pendingLaunch === launch) it.copy(pendingLaunch = null) else it }
    }

    fun showToast(message: UiText, success: Boolean = false) {
        toastJob?.cancel()
        mutable.update { it.copy(toastMessage = message, toastIsSuccess = success) }
        toastJob = viewModelScope.launch {
            delay(2500)
            mutable.update { it.copy(toastMessage = null) }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AppViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return AppViewModel(AppEntryPreferences(context.applicationContext)) as T
        }
    }
}
