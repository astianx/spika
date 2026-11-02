package org.astianx.midori.one

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import org.astianx.midori.one.chat.*

class MainActivity : ComponentActivity() {
    private val app: AppViewModel by viewModels { AppViewModel.Factory(applicationContext) }

    private fun handleIntent(intent: Intent, initial: Boolean = false) {
        val contact = intent.data?.let {
            if (it.scheme == "xmpp" || (it.scheme == "midori" && it.host == "contact"))
                it.toString().takeIf { value -> value.length <= 2048 } else null
        }
        val peer = intent.getStringExtra(ChatNotifications.PEER_EXTRA)?.takeIf { it.length <= 2048 }
        val connection = intent.action == ChatConnectionService.OPEN_CONNECTION
        if (contact != null || peer != null || connection || intent.action == ChatNotifications.OPEN_MESSAGES) {
            app.acceptLaunch(AppLaunch(contact, peer, connection), initial)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!androidx.emoji2.text.EmojiCompat.isConfigured()) {
            androidx.emoji2.text.EmojiCompat.init(androidx.emoji2.bundled.BundledEmojiCompatConfig(applicationContext))
        }
        enableEdgeToEdge()
        handleIntent(intent, initial = true)
        setContent {
            val preferences = remember { ThemePreferences(applicationContext) }
            val styleStore = remember { PersonalizationStore(applicationContext) }
            val appStyle by styleStore.state.collectAsState()
            var themeMode by remember { mutableStateOf(preferences.load()) }
            val darkTheme = themeMode.isDark(isSystemInDarkTheme())
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(0xFFFFFFFF.toInt(), 0xFF111518.toInt()) { darkTheme }
                )
            }
            MidoriTheme(darkTheme, appStyle) {
                val chat = remember { ChatSession.get(applicationContext) }
                val chatStatus by chat.state.collectAsState()
                val appStatus by app.state.collectAsState()
                LaunchedEffect(appStatus.pendingLaunch, chatStatus.restoringSession, chatStatus.account) {
                    val launch = appStatus.pendingLaunch ?: return@LaunchedEffect
                    if (chatStatus.restoringSession) return@LaunchedEffect
                    when {
                        launch.contactLink != null -> {
                            chat.closeConversation()
                            chat.prepareContact(launch.contactLink)
                            app.navigate(Screen.Chats)
                        }
                        launch.connectionSettings -> {
                            chat.closeConversation()
                            app.navigate(Screen.Chats)
                            app.navigate(Screen.Messaging)
                        }
                        launch.peer != null -> {
                            if (chatStatus.account.isBlank()) {
                                app.navigate(Screen.Chats)
                                app.navigate(Screen.Messaging)
                                return@LaunchedEffect
                            }
                            chat.open(launch.peer).join()
                            app.navigate(if (chat.state.value.selectedPeer == launch.peer) Screen.Conversation else Screen.Chats)
                        }
                        else -> {
                            chat.closeConversation()
                            app.navigate(Screen.Chats)
                        }
                    }
                    app.consumeLaunch(launch)
                }
                val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                LaunchedEffect(chatStatus.account, chatStatus.backgroundConnection, chatStatus.restoringSession, lifecycleOwner) {
                    if (chatStatus.restoringSession) return@LaunchedEffect
                    lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                        if (chatStatus.account.isNotBlank() && chatStatus.backgroundConnection) {
                            ChatConnectionService.start(applicationContext)
                        } else ChatConnectionService.stop(applicationContext)
                    }
                }
                DisposableEffect(lifecycleOwner) {
                    chat.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_START) chat.setForeground(true)
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) chat.setForeground(false)
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                CompositionLocalProvider(LocalChatAppearance provides chatStatus.appearance) {
                    MidoriApp(app, chat, themeMode, styleStore::save) { selected ->
                        preferences.save(selected)
                        themeMode = selected
                    }
                }
            }
        }
    }
}

@Composable
fun MidoriApp(app: AppViewModel, chat: ChatViewModel, themeMode: ThemeMode,
    onStyleChange: (AppStyle) -> Unit, onThemeChange: (ThemeMode) -> Unit) {
    val s by app.state.collectAsState()
    val chatState by chat.state.collectAsState()
    val scanContact = rememberContactScanner { chat.prepareContact(it); app.navigate(Screen.Chats) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing)) {
        val isMainScreen = s.currentScreen in mainScreens
        BackHandler(enabled = isMainScreen && s.currentScreen != Screen.Chats) { app.navigate(Screen.Chats) }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (s.currentScreen) {
                    Screen.Welcome -> WelcomeScreen(app, themeMode, onThemeChange)
                    Screen.Messaging -> MessagingScreen(chat, app::navigate, s.messagingReturnScreen)
                    Screen.Chats -> ChatsScreen(chat, app::navigate, scanContact)
                    Screen.More -> MoreScreen(app, chat, scanContact)
                    Screen.Feature -> FeatureScreen(app)
                    Screen.Help -> HelpScreen(app)
                    Screen.Conversation -> CompositionLocalProvider(LocalChatAppearance provides chatState.appearanceFor(chatState.selectedPeer)) {
                        ConversationScreen(chat, app::navigate)
                    }
                    Screen.ChatSecurity -> ChatSecurityScreen(chat, app::navigate)
                    Screen.Licenses -> LicensesScreen(app)
                    Screen.GroupDetails -> GroupDetailsScreen(chat, app::navigate)
                    Screen.Personalization -> PersonalizationScreen(chat, themeMode, onThemeChange, onStyleChange, app::navigate)
                    Screen.ChatAppearance -> ChatAppearanceScreen(chat) { app.navigate(Screen.Personalization) }
                    Screen.ConversationAppearance -> ChatAppearanceScreen(chat, chatState.selectedPeer) { app.navigate(Screen.Conversation) }
                    Screen.Profile -> ProfileScreen(chat, app::navigate)
                }
            }
            if (isMainScreen) AppNavigation(s.currentScreen, app::navigate, chatState.unreadCount)
        }
        s.toastMessage?.let { message ->
            Text(message.asString(), color = if (s.toastIsSuccess) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onErrorContainer, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (s.toastIsSuccess) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}
