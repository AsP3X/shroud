package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule

/**
 * On-device transcription (00-plan §1.7.13, P7). Owner: W2-WHISPER (native engine, model
 * store), then W3-TRANSCRIPTION — the `VoiceTranscription` behind the seam W2-INT publishes.
 *
 * Created empty by W0-A; only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else
 * constructs this package's classes: other packages reach them through this module.
 */
class TranscriptionModule(container: AppContainer) : AppModule(container)
