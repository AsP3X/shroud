package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.calls.CallPush
import de.corespace.shroud.core.calls.CallSystem
import de.corespace.shroud.core.calls.system.AndroidCallSystem
import de.corespace.shroud.core.calls.system.AndroidForegroundStarter
import de.corespace.shroud.core.calls.system.CallNotices
import de.corespace.shroud.core.calls.system.CallScreenHooks
import de.corespace.shroud.core.calls.system.CallSystemCallbacks
import de.corespace.shroud.core.calls.system.CallSystemRegistry
import de.corespace.shroud.core.calls.system.CoreCallTelecom
import de.corespace.shroud.core.calls.system.NotificationManagerShade
import de.corespace.shroud.core.calls.system.PlatformCallAudio
import de.corespace.shroud.core.calls.system.PlatformCallRinger
import de.corespace.shroud.core.calls.system.PlatformProximity
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Call system integration (00-plan §1.7.11). Telecom registration runs in [onProcessStart].
 * G9 attaches this [system] to the call controller; this module does not call `CallController.attach`.
 *
 * G3's push dispatcher must deliver a call push through [onCallPush] (or
 * [de.corespace.shroud.core.calls.CallController.handleCallPush] directly). That path calls
 * [CallSystem.reportIncoming]. [de.corespace.shroud.core.push] is still a stub, so nothing else
 * rings from a push until G3 wires it. Until G9 attaches the system, [onCallPush] reaches the
 * controller and does not ring.
 */
class CallsSystemModule(container: AppContainer) : AppModule(container) {
    private val systemLazy = lazy { create() }

    val system: CallSystem by systemLazy

    val screenHooks: CallScreenHooks
        get() = system as CallScreenHooks

    override fun onProcessStart() {
        val created = system as AndroidCallSystem
        created.start()
        container.appScope.launch {
            container.appPhase.phase.collect { created.onForegroundChanged() }
        }
    }

    /**
     * Ring entry while the push dispatcher is a stub. G3 must call
     * [de.corespace.shroud.core.calls.CallController.handleCallPush], which reports the incoming
     * call through the attached [CallSystem].
     */
    fun onCallPush(push: CallPush) {
        container.calls.controller.handleCallPush(push)
    }

    private fun create(): AndroidCallSystem {
        val app = container.appContext
        val created = AndroidCallSystem(
            context = app,
            notices = CallNotices(app),
            shade = NotificationManagerShade(app),
            starter = AndroidForegroundStarter(app),
            telecom = CoreCallTelecom(app, container.appScope),
            ringer = PlatformCallRinger(app),
            audio = PlatformCallAudio(app),
            proximity = PlatformProximity(app),
            callbacks = object : CallSystemCallbacks {
                override fun onAnswer(callId: UUID) = container.calls.controller.telecomAnswer(callId)
                override fun onEnd(callId: UUID) = container.calls.controller.telecomEnd(callId)
                override fun onMute(callId: UUID, muted: Boolean) = container.calls.controller.telecomMute(callId, muted)
                override fun onToggleSpeaker() = container.calls.controller.toggleSpeaker()
            },
            missedChannelId = { container.notifications.channels.missedCalls() },
            appInForeground = { container.appPhase.isResumed },
        )
        CallSystemRegistry.current = created
        return created
    }
}
