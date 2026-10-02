package de.corespace.shroud.core.push

import de.corespace.shroud.core.push.backgroundconnection.BackgroundConnectionController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hold and release without a real service. Boot is the pure [BackgroundConnectionController.shouldRestart]. */
class BackgroundConnectionControllerTest {
    @Test
    fun enableTwiceKeepsOneService() {
        val fx = fixture()
        fx.controller.enable()
        fx.controller.enable()
        assertEquals(1, fx.host.starts)
        assertTrue(fx.prefs.backgroundConnection)
        assertTrue(fx.controller.requested)
    }

    @Test
    fun holdOnceAndForgetReleases() {
        val fx = fixture()
        fx.controller.enable()
        fx.controller.onServiceStarted()
        fx.controller.onServiceStarted()
        assertEquals(1, fx.counts.holds)
        assertEquals(0, fx.counts.releases)
        fx.controller.forget()
        assertEquals(1, fx.host.stops)
        assertEquals(1, fx.counts.releases)
        assertEquals(1, fx.counts.ended)
        assertFalse(fx.controller.requested)
        fx.controller.forget()
        assertEquals(1, fx.counts.releases)
    }

    @Test
    fun stopKeepsThePreference() {
        val fx = fixture()
        fx.controller.enable()
        fx.controller.onServiceStarted()
        fx.controller.stopKeepPreference()
        assertFalse(fx.controller.requested)
        assertTrue(fx.prefs.backgroundConnection)
        assertEquals(1, fx.counts.releases)
    }

    @Test
    fun aStartWeDidNotAskForDoesNotHold() {
        val fx = fixture()
        fx.controller.onServiceStarted()
        assertEquals(0, fx.counts.holds)
        assertEquals(1, fx.host.stops)
    }

    @Test
    fun bootRestartIsPrefAndSessionAndUnlock() {
        assertTrue(BackgroundConnectionController.shouldRestart(prefOn = true, signedIn = true, userUnlocked = true))
        assertFalse(BackgroundConnectionController.shouldRestart(prefOn = true, signedIn = true, userUnlocked = false))
        assertFalse(BackgroundConnectionController.shouldRestart(prefOn = true, signedIn = false, userUnlocked = true))
        assertFalse(BackgroundConnectionController.shouldRestart(prefOn = false, signedIn = true, userUnlocked = true))
        val off = fixture()
        off.controller.restartAfterBoot(userUnlocked = true)
        assertEquals(0, off.host.starts)
        val on = fixture()
        on.prefs.backgroundConnection = true
        on.controller.restartAfterBoot(userUnlocked = false)
        assertEquals(0, on.host.starts)
        on.controller.restartAfterBoot(userUnlocked = true)
        assertEquals(1, on.host.starts)
    }

    private class Host : BackgroundConnectionController.Host {
        var starts = 0
        var stops = 0
        override fun start() {
            starts++
        }
        override fun stop() {
            stops++
        }
    }

    private class Prefs : PushSettings {
        override var backgroundConnection: Boolean = false
        override var distributorChoice: String? = null
        override var batteryPromptShown: Boolean = false
        override fun clear() {
            backgroundConnection = false
            distributorChoice = null
            batteryPromptShown = false
        }
    }

    private class Counts {
        var holds = 0
        var releases = 0
        var ended = 0
    }

    private class Fixture(
        val host: Host,
        val prefs: Prefs,
        val controller: BackgroundConnectionController,
        val counts: Counts,
    )

    private fun fixture(): Fixture {
        val host = Host()
        val prefs = Prefs()
        val counts = Counts()
        val controller = BackgroundConnectionController(
            host = host,
            prefs = prefs,
            sessionToken = { "session-token" },
            hold = { counts.holds++ },
            releaseHold = { counts.releases++ },
            onEnded = { counts.ended++ },
            battery = object : BackgroundConnectionController.BatteryGate {
                override fun unrestricted() = true
                override fun askOnceIfNeeded() = Unit
            },
            reconnect = object : BackgroundConnectionController.ReconnectSchedule {
                override fun arm() = Unit
                override fun cancel() = Unit
                override fun pulse(reconnect: () -> Unit) = reconnect()
            },
            onReconnect = {},
        )
        return Fixture(host, prefs, controller, counts)
    }
}
