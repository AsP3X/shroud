package de.corespace.shroud.core.transcription

/**
 * The only decode contract chat and recording talk to (iOS `TranscriptionEngine.swift:9-21`;
 * media-voice-links §9.1). Android takes 16 kHz mono float PCM; decoding the container happens
 * before this (`AudioPcmDecoder`). A new backend is a new type; [TranscriptionSession] owns it.
 *
 * Throws [TranscriptionEngineError]. Nothing here sends audio or text off the device.
 */
interface TranscriptionEngine {
    /** Stable id, also the prefs suffix of `transcription.<id>.ready` so a test double cannot mark Whisper installed. */
    val id: String

    /** Downloads (if this backend does) and loads [model]. [progress] is 0…1. */
    suspend fun prepare(model: TranscriptionModelId, progress: ((Double) -> Unit)? = null)

    /** Transcribes [pcm16k]. Detects the language when [TranscriptionRequest.language] is null. */
    suspend fun transcribe(pcm16k: FloatArray, request: TranscriptionRequest): TranscriptionOutput
}
