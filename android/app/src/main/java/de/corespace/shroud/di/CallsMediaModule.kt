package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule

/**
 * Call media (00-plan §1.7.11). Owner: W3-CALLS-MEDIA — the WebRTC `CallMediaEngine` (peer
 * connection factory, camera, screen capture).
 *
 * Created empty by W0-A; only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else
 * constructs this package's classes: other packages reach them through this module.
 */
class CallsMediaModule(container: AppContainer) : AppModule(container)
