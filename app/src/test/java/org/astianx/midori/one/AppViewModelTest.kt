package org.astianx.midori.one

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); Dispatchers.resetMain() }
    private fun app(store: AppEntryStore = MemoryAppEntryStore()) = AppViewModel(store).also {
        models.put("app-${models.keys().size}", it)
    }

    @Test fun introductionAndRestartNeedOnlyTheAppPreference() {
        val entry = MemoryAppEntryStore()
        val first = app(entry)
        assertEquals(Screen.Welcome, first.state.value.currentScreen)
        first.updateIntroductionPage(2)
        first.navigate(Screen.Messaging)
        assertEquals(Screen.Welcome, first.state.value.messagingReturnScreen)
        first.navigate(first.state.value.messagingReturnScreen)
        assertEquals(2, first.state.value.introductionPage)
        first.enterApp()
        assertTrue(entry.hasEntered())
        assertEquals(Screen.Chats, app(entry).state.value.currentScreen)
    }

    @Test fun nestedAccountHelpAndIntroductionKeepTheirReturnDestinations() {
        val app = app()
        app.enterApp()
        app.navigate(Screen.More)
        app.openFeature(AppFeature.DESKTOP)
        app.navigate(Screen.Messaging)
        app.navigate(Screen.Messaging) // Repeated external settings requests do not lose the return.
        assertEquals(Screen.Feature, app.state.value.messagingReturnScreen)
        app.navigate(app.state.value.messagingReturnScreen)
        assertEquals(Screen.More, app.state.value.detailReturnScreen)
        app.navigate(app.state.value.detailReturnScreen)
        app.navigate(Screen.Help)
        app.navigate(app.state.value.detailReturnScreen)
        assertEquals(Screen.More, app.state.value.currentScreen)
        app.navigate(Screen.Welcome)
        assertEquals(Screen.More, app.state.value.introductionReturnScreen)
        app.navigate(Screen.Messaging)
        assertEquals(Screen.Welcome, app.state.value.messagingReturnScreen)
    }

    @Test fun consumingAnOldLaunchCannotDiscardANewerContactRequest() {
        val app = app()
        val notification = AppLaunch(peer = "peer@localhost")
        val contact = AppLaunch(contactLink = "xmpp:contact@localhost")
        app.acceptLaunch(notification)
        app.acceptLaunch(contact)
        app.consumeLaunch(notification)
        assertSame(contact, app.state.value.pendingLaunch)
        app.consumeLaunch(contact)
        assertNull(app.state.value.pendingLaunch)
    }

    @Test fun activityRecreationDoesNotReplayAConsumedInitialLink() {
        val app = app()
        val first = AppLaunch(contactLink = "xmpp:peer@localhost")
        app.acceptLaunch(first, initial = true)
        app.consumeLaunch(first)
        app.acceptLaunch(first.copy(), initial = true)
        assertNull(app.state.value.pendingLaunch)
        val later = AppLaunch(peer = "peer@localhost")
        app.acceptLaunch(later)
        assertSame(later, app.state.value.pendingLaunch)
    }

    @Test fun repeatedIdenticalNoticeGetsItsOwnDisplayTime() = runTest(dispatcher) {
        val app = app()
        val text = UiText(R.string.style_imported)
        app.showToast(text)
        runCurrent()
        advanceTimeBy(2000)
        app.showToast(text, success = true)
        runCurrent()
        advanceTimeBy(600)
        assertEquals(text, app.state.value.toastMessage)
        assertTrue(app.state.value.toastIsSuccess)
        advanceTimeBy(1900)
        runCurrent()
        assertNull(app.state.value.toastMessage)
    }
}
