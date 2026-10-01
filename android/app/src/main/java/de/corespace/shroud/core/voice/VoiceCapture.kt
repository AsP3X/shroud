package de.corespace.shroud.core.voice

import java.io.File

/**
 * The two platform halves of a voice take, behind interfaces so [VoiceRecorder]'s take logic runs on
 * the JVM with fakes (plan §2.0 rule 6): the microphone ([PcmSource], [AudioRecordPcmSource] on a
 * device) and the AAC/M4A encoder ([VoiceEncoder], [AacM4aWriter]). Both are owned by one recording
 * loop thread at a time; neither is thread-safe.
 *
 * The format is iOS's (`ios/shroud/Services/Voice/VoiceRecorder.swift:82-89`): 16-bit PCM,
 * [SAMPLE_RATE] Hz, mono, in; AAC-LC in an MPEG-4 audio file (`audio/mp4`) out
 * (media-voice-links §8.1, D8).
 */
object VoiceFormat {
    /** 44 100 Hz, `AVSampleRateKey: 44_100` (`VoiceRecorder.swift:86`). */
    const val SAMPLE_RATE = 44_100

    /** Mono, `AVNumberOfChannelsKey: 1` (`VoiceRecorder.swift:87`). */
    const val CHANNELS = 1

    /**
     * AAC-LC bitrate. iOS leaves it to `AVAudioQuality.high`; 64 kbps (≈ 480 KB per minute) is the
     * spec's pick for a mono speech note (media-voice-links §8.1).
     */
    const val AAC_BITRATE = 64_000

    /** The wire MIME of a voice note (`MessagingController.swift:3649`). */
    const val MIME = "audio/mp4"

    /** The temp file's stem and extension: `cacheDir/shroud-voice-<random>.m4a` (`VoiceRecorder.swift:82-83`). */
    const val TEMP_STEM = "voice"
    const val TEMP_EXTENSION = "m4a"
}

/** A blocking source of 16-bit mono PCM at [VoiceFormat.SAMPLE_RATE] — the microphone. */
interface PcmSource {
    /**
     * Starts capturing. Throws when the microphone cannot start (busy, no permission, no device):
     * iOS's `recorder.record()` returning false (`VoiceRecorder.swift:92`).
     */
    fun start()

    /**
     * Reads up to [size] frames into `buffer[offset…]`, blocking until they are captured. Returns the
     * number of frames read, or a negative value when the source failed.
     */
    fun read(buffer: ShortArray, offset: Int, size: Int): Int

    /** Stops capturing. Never throws. */
    fun stop()

    /** Frees the microphone. Never throws; safe after [stop] or without [start]. */
    fun release()
}

/** Encodes PCM from a [PcmSource] into an `.m4a` file. */
interface VoiceEncoder {
    /** Encodes `pcm[offset until offset + size]`, frames in capture order. */
    fun write(pcm: ShortArray, offset: Int, size: Int)

    /** Drains the encoder and completes the file. Throws when no valid file could be written. */
    fun finish()

    /** Releases the encoder without completing the file (the caller deletes it). Never throws. */
    fun abort()
}

/** Builds the platform halves of one take; [VoiceRecorder] asks for a fresh pair per take. */
interface VoiceCaptureFactory {
    fun openSource(): PcmSource
    fun openEncoder(output: File): VoiceEncoder
}
