package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.calls.CallMediaEngine
import de.corespace.shroud.core.calls.media.CallMediaEngine as WebRtcCallMediaEngine

/**
 * Call media (00-plan §1.7.11). The WebRTC [CallMediaEngine]: peer connection, camera and screen
 * capture. Built with no session, so a ring can be answered before chats unlock. Not attached to
 * the call controller here — that wiring is a later step. Constructing [engine] does not load the
 * native library; the first [de.corespace.shroud.core.calls.CallMediaEngine.start] does.
 *
 * Nobody else constructs this package's classes: other packages reach them through this module.
 */
class CallsMediaModule(container: AppContainer) : AppModule(container) {
    /** The process's call engine. `container.callsMedia.engine`. */
    val engine: CallMediaEngine by lazy { WebRtcCallMediaEngine(container.appContext) }
}
