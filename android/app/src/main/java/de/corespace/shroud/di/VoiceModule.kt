package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule

/**
 * Voice messages (00-plan §1.7.9, C24). Owner: W2-VOICE — `VoiceRecorder`,
 * `VoicePlaybackCoordinator`, `AudioFocusCoordinator`.
 *
 * Created empty by W0-A; only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else
 * constructs this package's classes: other packages reach them through this module.
 */
class VoiceModule(container: AppContainer) : AppModule(container)
