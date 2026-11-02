/* Copyright 2026 Astian. Licensed under the Apache License, Version 2.0. */
package org.astianx.midori.one.chat

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.astianx.midori.one.MainActivity
import org.astianx.midori.one.R
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android launches, independent of ActivityScenario's task/lifecycle ownership. */
@RunWith(AndroidJUnit4::class)
class AppLaunchDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun contactConsentAndNotificationUseOnlyTheChatSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configuration = File(context.filesDir, "chat-live-ui.json")
        assumeTrue("Explicit live UI laboratory configuration is required", configuration.exists())
        val config = JSONObject(configuration.readText())
        val peer = config.getString("second")
        // Explicit opt-in laboratory acceptance exercises the permission-enabled path.
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        lateinit var chat: ChatViewModel
        instrumentation.runOnMainSync { chat = ChatSession.get(context) }
        compose.waitUntil(15_000) { !chat.state.value.restoringSession }
        assertEquals(config.getString("first"), chat.state.value.account)
        val before = chat.state.value.contacts.map { it.address }.toSet()
        fun launch(action: String, data: String? = null) {
            context.startActivity(Intent(context, MainActivity::class.java).setAction(action)
                .setData(data?.let(Uri::parse))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        fun capture(name: String) {
            compose.waitForIdle()
            Thread.sleep(250)
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val dir = File(context.cacheDir, "live-ui").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        launch(Intent.ACTION_VIEW, chatContactLink(peer))
        compose.waitUntil(10_000) {
            chat.state.value.pendingContactAddress == peer &&
                compose.onAllNodesWithText(context.getString(R.string.contacts_save_chat)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.contacts_save_chat)).assertIsDisplayed()
        assertEquals(before, chat.state.value.contacts.map { it.address }.toSet())
        capture("contact-link-review")
        compose.onNodeWithText(context.getString(R.string.cancel)).performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitUntil(10_000) { chat.state.value.pendingContactAddress == null }

        val received = chat.state.value.messages.last { !it.outgoing && it.peer == peer && it.body == config.getString("reply") }
        val notifications = ChatNotifications(context)
        notifications.show(received, 1)
        val manager = context.getSystemService(NotificationManager::class.java)
        compose.waitUntil(10_000) { manager.activeNotifications.any { it.tag == peer } }
        manager.activeNotifications.first { it.tag == peer }.notification.contentIntent.send()
        compose.waitUntil(10_000) {
            chat.state.value.selectedPeer == peer && compose.onAllNodesWithContentDescription(context.getString(R.string.chat_options))
                .fetchSemanticsNodes().isNotEmpty()
        }
        capture("notification-conversation")
        launch(ChatConnectionService.OPEN_CONNECTION)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(R.string.chat_account_title)).fetchSemanticsNodes().isNotEmpty() }
        launch(ChatNotifications.OPEN_MESSAGES)
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(context.getString(R.string.chats)) and isSelectable()).fetchSemanticsNodes().isNotEmpty() }
        assertNull(chat.state.value.selectedPeer)
        File(context.cacheDir, "live-ui/launch-report.json").writeText(JSONObject()
            .put("contactLinkRequiresConfirmation", true).put("notificationOpensConversation", true)
            .put("connectionNotificationOpensSettings", true).put("summaryOpensChats", true).toString(2))
    }
}
