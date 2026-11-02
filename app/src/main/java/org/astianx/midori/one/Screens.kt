package org.astianx.midori.one

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.astianx.midori.one.chat.label
import org.astianx.midori.one.chat.ChatViewModel

@Composable
fun BrandSplashScreen() {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        MidoriLogo(Modifier.width(180.dp))
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.brand_name), color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
    }
}

// ===== WELCOME SCREEN =====

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WelcomeScreen(vm: AppViewModel, themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    val s by vm.state.collectAsState()
    BackHandler(enabled = s.hasEnteredApp) { vm.navigate(s.introductionReturnScreen) }
    val slides = IntroSlide.entries
    val pager = rememberPagerState(initialPage = s.introductionPage) { slides.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { vm.updateIntroductionPage(it) }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val shortWindow = maxHeight < 520.dp
        val artworkHeight = if (shortWindow) 140.dp else (maxHeight * 0.28f).coerceIn(140.dp, 220.dp)
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxSize()
                .then(if (shortWindow) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MidoriLogo(Modifier.width(38.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.app_name), fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                ThemePicker(themeMode, onThemeChange, compact = true)
            }

            HorizontalPager(
                state = pager,
                verticalAlignment = Alignment.Top,
                modifier = if (shortWindow) Modifier.height(460.dp) else Modifier.weight(1f)
            ) { page ->
                val slide = slides[page]
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    OnboardingArtwork(slide, Modifier.fillMaxWidth().height(artworkHeight))
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(slide.tag),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(slide.title), style = MaterialTheme.typography.headlineLarge,
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(slide.body), style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (slide == IntroSlide.MESSAGING) {
                            TextButton(onClick = { vm.navigate(Screen.Messaging) }) {
                                Text(stringResource(R.string.view_messaging), fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            OnboardingControls(slides, pager.currentPage, enabled = !pager.isScrollInProgress) { page ->
                scope.launch { pager.animateScrollToPage(page) }
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = vm::enterApp,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                ) {
                    Text(stringResource(if (s.hasEnteredApp) R.string.go_to_chats else R.string.get_started),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(10.dp))
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.onboarding_version, BuildConfig.VERSION_NAME),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MoreScreen(vm: AppViewModel, chat: ChatViewModel, onScan: () -> Unit) {
    val chatState by chat.state.collectAsState()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        MainHeader(stringResource(R.string.more), onScan = onScan,
            onProfile = { vm.navigate(Screen.Profile) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth()
                .clickable { vm.navigate(Screen.Profile) }.padding(horizontal = 16.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(60.dp).clip(androidx.compose.foundation.shape.CircleShape)) {
                    org.astianx.midori.one.chat.ChatAvatar(chatState.profile.name.ifBlank { chatState.account.ifBlank { "M" } }, Modifier.fillMaxSize())
                    org.astianx.midori.one.chat.LocalArtwork(chatState.profile.photo, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(chatState.profile.name.ifBlank { stringResource(R.string.profile) }, style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(chatState.account.ifBlank { stringResource(R.string.chat_account_title) },
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel(R.string.preferences)
            ShortcutRow(Icons.Outlined.Lock, stringResource(R.string.chat_privacy),
                stringResource(R.string.chat_encrypted), { vm.navigate(Screen.Messaging) })
            ShortcutRow(Icons.Outlined.Description, stringResource(R.string.chat_licenses),
                stringResource(R.string.app_name), { vm.navigate(Screen.Licenses) })
            ShortcutRow(Icons.Outlined.Palette, stringResource(R.string.style_title),
                stringResource(R.string.style_summary), { vm.navigate(Screen.Personalization) })
            ShortcutRow(Icons.Outlined.Wifi, stringResource(R.string.chat_background_title),
                stringResource(chatState.connection.label()), { vm.navigate(Screen.Messaging) })
            ShortcutRow(Icons.Outlined.NotificationsNone, stringResource(R.string.chat_notifications_title),
                stringResource(R.string.chat_notifications_summary), {
                    context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                })
            ShortcutRow(Icons.Outlined.Language, stringResource(R.string.app_language), stringResource(R.string.app_language_body), {
                context.startActivity(Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS,
                    Uri.parse("package:${context.packageName}")))
            })
            ShortcutRow(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.help_center),
                stringResource(R.string.help_shortcut_body), { vm.navigate(Screen.Help) })
            ShortcutRow(Icons.Outlined.Explore, stringResource(R.string.presentation),
                stringResource(R.string.onboarding_version, BuildConfig.VERSION_NAME), { vm.navigate(Screen.Welcome) })
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun FeatureScreen(vm: AppViewModel) {
    val s by vm.state.collectAsState()
    BackHandler { vm.navigate(s.detailReturnScreen) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(s.feature.title)) { vm.navigate(s.detailReturnScreen) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp)) {
            Icon(featureIcon(s.feature), null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.in_development), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(s.feature.description), fontSize = 18.sp, lineHeight = 28.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(24.dp))

        }
    }
}

@Composable
fun HelpScreen(vm: AppViewModel) {
    val s by vm.state.collectAsState()
    BackHandler { vm.navigate(s.detailReturnScreen) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.help_center)) { vm.navigate(s.detailReturnScreen) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp)) {
            listOf(R.string.help_chat_account_title to R.string.help_chat_account_body,
                R.string.help_chat_contacts_title to R.string.help_chat_contacts_body,
                R.string.help_chat_privacy_title to R.string.help_chat_privacy_body,
                R.string.help_chat_delivery_title to R.string.help_chat_delivery_body).forEach { (title, body) ->
                Text(stringResource(title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(body), fontSize = 15.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun LicensesScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val text = androidx.compose.runtime.remember { context.assets.open("messaging-notices.txt").bufferedReader().use { it.readText() } }
    BackHandler { vm.navigate(Screen.More) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.chat_licenses)) { vm.navigate(Screen.More) }
        Text(text, Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), fontSize = 13.sp, lineHeight = 20.sp)
    }
}
