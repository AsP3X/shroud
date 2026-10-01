package de.corespace.shroud.core.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException
import kotlin.math.max

/**
 * The microphone as a [PcmSource]: `AudioRecord(MIC, 44 100 Hz, mono, PCM 16)` with a buffer of
 * `max(minBufferSize × 2, 4096)` bytes (media-voice-links §8.1). `MIC`, not `VOICE_COMMUNICATION`:
 * iOS records in the default session mode without voice processing (`ChatAudioSession.swift:23`).
 *
 * Android 14+ refuses to start capture from the background and silences it there without a
 * `microphone` foreground service; voice notes have none, so [VoiceRecorder] cancels the take when the
 * app leaves the foreground (iOS cancels when the thread disappears, `ConversationView.swift:363-379`).
 */
class AudioRecordPcmSource(private val context: Context) : PcmSource {
    private var record: AudioRecord? = null

    // RECORD_AUDIO is checked right here (and by VoiceRecorder before it opens a source).
    @SuppressLint("MissingPermission")
    override fun start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO not granted")
        }
        val minimum = AudioRecord.getMinBufferSize(VoiceFormat.SAMPLE_RATE, CHANNEL_MASK, ENCODING)
        if (minimum <= 0) throw IOException("no microphone configuration")
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, VoiceFormat.SAMPLE_RATE, CHANNEL_MASK, ENCODING, max(minimum * 2, MIN_BUFFER_BYTES))
        } catch (e: IllegalArgumentException) {
            throw IOException("microphone unavailable", e)
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("microphone unavailable")
        }
        record = recorder
        try {
            recorder.startRecording()
        } catch (e: IllegalStateException) {
            throw IOException("microphone did not start", e)
        }
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("microphone busy")
    }

    override fun read(buffer: ShortArray, offset: Int, size: Int): Int = record?.read(buffer, offset, size) ?: -1

    override fun stop() {
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
            // Never started.
        }
    }

    override fun release() {
        record?.release()
        record = null
    }

    private companion object {
        const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val MIN_BUFFER_BYTES = 4096
    }
}
