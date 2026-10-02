package de.corespace.shroud.ui.settings.push

import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.push.Distributor
import de.corespace.shroud.core.push.NoPushReason
import de.corespace.shroud.core.push.PushDelivery
import de.corespace.shroud.core.push.PushRegistration
import de.corespace.shroud.core.push.UnifiedPushState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Delivery screen's actions on a K6 fake: they forward to `push.registration` one to one,
 * tick lightly, and never register, start, stop or forget on their own (C15: "never call
 * register/forget from UI beyond what the contract's UI methods do").
 */
class PushDeliveryModelTest {
    private class FakeRegistration : PushRegistration {
        val state = MutableStateFlow(PushDelivery(UnifiedPushState.Unavailable(NoPushReason.NoneChosen), backgroundConnection = false, batteryUnrestricted = false))
        var installed = listOf<Distributor>()
        val calls = ArrayList<String>()

        override val delivery: StateFlow<PushDelivery> get() = state
        override fun start() { calls += "start" }
        override fun stop() { calls += "stop" }
        override fun register() { calls += "register" }
        override fun onSystemSettingsMaybeChanged() { calls += "systemSettings" }
        override fun distributors(): List<Distributor> {
            calls += "distributors"
            return installed
        }
        override fun chooseDistributor(packageName: String?) { calls += "choose:$packageName" }
        override fun setBackgroundConnection(enabled: Boolean) {
            calls += "background:$enabled"
            state.value = state.value.copy(backgroundConnection = enabled)
        }
        override suspend fun forgetRegistration() { calls += "forget" }
    }

    private val registration = FakeRegistration()
    private val haptics = ArrayList<Haptic>()
    private val model = PushDeliveryModel(registration, haptic = { haptics += it })

    @Test
    fun aResumeReadsTheDistributorsAndTheSystemState() {
        val ntfy = Distributor("io.heckel.ntfy", "ntfy")
        registration.installed = listOf(ntfy)
        model.onResume()
        assertEquals(listOf(ntfy), model.distributors.value)
        assertEquals(listOf("distributors", "systemSettings"), registration.calls)
        // A distributor installed meanwhile shows on the next resume.
        val nextPush = Distributor("org.unifiedpush.distributor.nextpush", "NextPush")
        registration.installed = listOf(ntfy, nextPush)
        model.onResume()
        assertEquals(listOf(ntfy, nextPush), model.distributors.value)
        assertEquals(emptyList<Haptic>(), haptics)
    }

    @Test
    fun choosingADistributorOrNone() {
        model.choose(DistributorChoice.App("io.heckel.ntfy", "ntfy"))
        model.choose(DistributorChoice.None)
        // "Not connected" is only ever shown, never chosen.
        model.choose(DistributorChoice.NotConnected)
        assertEquals(listOf("choose:io.heckel.ntfy", "choose:null"), registration.calls)
        assertEquals(listOf(Haptic.Light, Haptic.Light), haptics)
    }

    @Test
    fun theBackgroundConnectionSwitch() {
        model.setBackgroundConnection(true)
        // The same value again (a recomposition, a double tap) does nothing.
        model.setBackgroundConnection(true)
        model.setBackgroundConnection(false)
        assertEquals(listOf("background:true", "background:false"), registration.calls)
        assertEquals(listOf(Haptic.Light, Haptic.Light), haptics)
    }

    @Test
    fun nothingRegistersOrForgetsOnItsOwn() {
        model.onResume()
        model.choose(DistributorChoice.None)
        model.setBackgroundConnection(true)
        for (forbidden in listOf("register", "forget", "start", "stop")) {
            assertEquals(forbidden, 0, registration.calls.count { it == forbidden })
        }
    }
}
