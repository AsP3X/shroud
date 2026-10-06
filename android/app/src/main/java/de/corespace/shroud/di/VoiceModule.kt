package de.corespace.shroud.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.lifecycle.AppPhase
import de.corespace.shroud.core.messaging.MessageArtifactSinks
import de.corespace.shroud.core.voice.AacM4aWriter
import de.corespace.shroud.core.voice.AndroidFocusPort
import de.corespace.shroud.core.voice.AudioFilePlaybackCoordinator
import de.corespace.shroud.core.voice.AudioFileQueue
import de.corespace.shroud.core.voice.AudioFocusCoordinator
import de.corespace.shroud.core.voice.AudioRecordPcmSource
import de.corespace.shroud.core.voice.ExoAudioFilePlayer
import de.corespace.shroud.core.voice.ExoVoicePlayer
import de.corespace.shroud.core.voice.PcmSource
import de.corespace.shroud.core.voice.VoiceCaptureFactory
import de.corespace.shroud.core.voice.VoiceEncoder
import de.corespace.shroud.core.voice.VoiceFormat
import de.corespace.shroud.core.voice.VoicePlaybackCoordinator
import de.corespace.shroud.core.voice.VoiceRecorder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Voice messages (00-plan §1.7.9, C24; media-voice-links §8). Owner: W2-VOICE. Builds the process's
 * one [VoiceRecorder] (one microphone), one [VoicePlaybackCoordinator] (one note at a time, iOS
 * `VoicePlaybackCoordinator.shared`), one [AudioFilePlaybackCoordinator] (audio files, docs/file-sharing.md
 * §11.5; one sound at a time with the notes) and the [AudioFocusCoordinator] they route through. Pure helpers
 * (`VoiceWaveform`, `VoiceTimeFormat`, `VoiceLevel`, `AudioPcmDecoder`) are objects in `core/voice`.
 *
 * Wiring the integration package does (W2-INT; this module cannot reach packages of the same wave):
 * - `messaging.controller.registerArtifactSink(voice.artifactSink)` — purges stop the deleted note,
 *   locks stop playback and discard a take, re-keys move the played mark;
 * - `voice.bindCallMediaStarting(calls.controller.callMediaStarting)` — a call's media cancels the take
 *   and stops playback (iOS `.shroudCallMediaStarting`);
 * - `WipeHooksImpl.haltWriters()` → [haltForWipe] (`DeviceWipeController.swift:92, 125`).
 *
 * Main-confined. Nobody else constructs these classes (00-plan §2.0 rule 3).
 */
class VoiceModule(container: AppContainer) : AppModule(container) {
    private val app: Context get() = container.appContext

    val audioFocus: AudioFocusCoordinator by lazy {
        AudioFocusCoordinator(AndroidFocusPort(app.getSystemService(AudioManager::class.java))).also { focus ->
            container.appScope.launch { focus.callMediaStarting.collect { onCallMediaStarting() } }
        }
    }

    private val recorderLazy = lazy {
        VoiceRecorder(
            scope = container.appScope,
            capture = object : VoiceCaptureFactory {
                override fun openSource(): PcmSource = AudioRecordPcmSource(app)
                override fun openEncoder(output: File): VoiceEncoder = AacM4aWriter(output)
            },
            audioFocus = audioFocus,
            hasPermission = { app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
            createTempFile = { container.keys.sensitiveTempFiles.create(VoiceFormat.TEMP_STEM, VoiceFormat.TEMP_EXTENSION) },
            appPhase = container.appPhase.phase,
        ).also { recorder ->
            // Playback and recording cannot share the route (`ConversationView.swift:1419-1420`): a note
            // paused by the take's exclusive focus would otherwise resume when the take ends.
            container.appScope.launch {
                recorder.state.map { it.recording }.distinctUntilChanged().collect { recording ->
                    if (recording && playbackLazy.isInitialized()) playback.stop()
                    if (recording && audioFilesLazy.isInitialized()) audioFiles.stop()
                }
            }
        }
    }

    private val playbackLazy: Lazy<VoicePlaybackCoordinator> = lazy {
        VoicePlaybackCoordinator(
            ExoVoicePlayer(app),
            container.appScope,
            // One sound at a time (docs/file-sharing.md §11.5): a voice note stops an audio file.
            onStarting = { if (audioFilesLazy.isInitialized()) audioFiles.stopForOtherSound() },
        )
    }

    private val audioFilesLazy: Lazy<AudioFilePlaybackCoordinator> = lazy {
        AudioFilePlaybackCoordinator(
            player = ExoAudioFilePlayer(app) { id -> container.media.dataSourceFactory(id) },
            scope = container.appScope,
            nextAfter = { id -> AudioFileQueue.nextAfter(container.messaging.controller.threads.value, id) },
            onStarting = { if (playbackLazy.isInitialized()) playback.stop() },
        ).also { files ->
            // iOS and Android pause when the app leaves the foreground (§11.5).
            container.appScope.launch {
                container.appPhase.phase.collect { if (it == AppPhase.Background) files.pauseForBackground() }
            }
        }
    }

    /**
     * The microphone: AAC-LC 44.1 kHz mono `.m4a` takes in `cacheDir/shroud-voice-*.m4a`
     * (`SensitiveTempFiles`), cancelled when the app goes to the background.
     */
    val recorder: VoiceRecorder by recorderLazy

    /** The app-wide voice-note player (Media3 ExoPlayer on the main looper). */
    val playback: VoicePlaybackCoordinator by playbackLazy

    /**
     * The app-wide audio-file player (docs/file-sharing.md §11.5): songs and recordings sent as files,
     * played from the sealed cache; one sound at a time with [playback].
     */
    val audioFiles: AudioFilePlaybackCoordinator by audioFilesLazy

    /**
     * For `MessagingController.registerArtifactSink` (W2-INT): purges stop the purged note and drop its
     * played mark, a re-key moves them to the server id, a lock stops playback and throws a take away
     * (`RootView` lock tears the thread down, `ConversationView.swift:363-379`). Builds nothing that was
     * never used.
     */
    val artifactSink: MessageArtifactSinks = object : MessageArtifactSinks {
        override fun onPurged(messageIds: Collection<UUID>) {
            if (playbackLazy.isInitialized()) playback.artifactSink.onPurged(messageIds)
            if (audioFilesLazy.isInitialized()) audioFiles.artifactSink.onPurged(messageIds)
        }

        override fun onSensitiveMemoryLocked() {
            haltForWipe()
            if (audioFilesLazy.isInitialized()) audioFiles.artifactSink.onSensitiveMemoryLocked()
        }

        override fun onMessageRekeyed(from: UUID, to: UUID) {
            if (playbackLazy.isInitialized()) playback.artifactSink.onMessageRekeyed(from, to)
            if (audioFilesLazy.isInitialized()) audioFiles.artifactSink.onMessageRekeyed(from, to)
        }
    }

    /** Forwards the calls area's `callMediaStarting` (W2-INT passes `CallController.callMediaStarting`). */
    fun bindCallMediaStarting(source: Flow<Unit>) {
        container.appScope.launch { source.collect { audioFocus.notifyCallMediaStarting() } }
    }

    /** The Log Out / removal wipe's writer stop: throw the take away, silence playback (`DeviceWipeController.swift:92, 125`). */
    fun haltForWipe() {
        if (recorderLazy.isInitialized()) recorder.cancel()
        if (playbackLazy.isInitialized()) playback.stop()
        if (audioFilesLazy.isInitialized()) audioFiles.stop()
    }

    /** iOS `.shroudCallMediaStarting` (`ConversationView.swift:380-383`, `CallController.swift:1948`). */
    private fun onCallMediaStarting() = haltForWipe()
}
