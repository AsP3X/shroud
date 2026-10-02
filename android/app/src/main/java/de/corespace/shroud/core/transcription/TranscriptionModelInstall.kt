package de.corespace.shroud.core.transcription

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Live download / transcribe status for the bubble and for Settings (iOS `TranscriptionModelInstall`,
 * `VoiceTranscriber.swift:12-72`). One session count, so a prefetch during a transcription does not
 * clear the bubble's progress, and [handOff] follows a note the server re-keyed.
 *
 * [state] is safe to update off the main thread. It never carries a transcript.
 */
class TranscriptionModelInstall {
    private val lock = Any()
    private val stateFlow = MutableStateFlow(TranscriptionInstallState.Idle)
    private var sessionCount = 0

    val state: StateFlow<TranscriptionInstallState> = stateFlow.asStateFlow()

    val isBusy: Boolean get() = stateFlow.value.phase != TranscriptionInstallState.Phase.Idle

    /** Opens a session only when nothing is running. The caller [finish]es only when this returns true. */
    fun openSessionIfIdle(): Boolean = synchronized(lock) {
        if (sessionCount > 0) return false
        beginLocked(null)
        true
    }

    fun begin(messageId: UUID?) = synchronized(lock) { beginLocked(messageId) }

    /** The note being transcribed was re-keyed; progress follows the new id (`handOff`, `:47-49`). */
    fun handOff(from: UUID, to: UUID) = synchronized(lock) {
        val current = stateFlow.value
        if (current.messageId == from) stateFlow.value = current.copy(messageId = to)
    }

    fun downloading(languageName: String, fraction: Double, determinate: Boolean) = synchronized(lock) {
        val current = stateFlow.value
        stateFlow.value = current.copy(
            phase = TranscriptionInstallState.Phase.Downloading,
            fractionCompleted = fraction.coerceIn(0.0, 1.0),
            isDeterminate = determinate,
            languageName = languageName,
        )
    }

    fun transcribing() = synchronized(lock) {
        val current = stateFlow.value
        stateFlow.value = current.copy(phase = TranscriptionInstallState.Phase.Transcribing, fractionCompleted = 1.0)
    }

    fun finish() = synchronized(lock) {
        sessionCount = (sessionCount - 1).coerceAtLeast(0)
        if (sessionCount == 0) stateFlow.value = TranscriptionInstallState.Idle
    }

    private fun beginLocked(messageId: UUID?) {
        sessionCount += 1
        val current = stateFlow.value
        val id = messageId ?: current.messageId
        stateFlow.value = if (current.phase == TranscriptionInstallState.Phase.Idle) {
            TranscriptionInstallState(
                phase = TranscriptionInstallState.Phase.Transcribing,
                fractionCompleted = 0.0,
                isDeterminate = false,
                languageName = null,
                messageId = id,
            )
        } else if (messageId != null) {
            current.copy(messageId = messageId)
        } else {
            current
        }
    }
}
