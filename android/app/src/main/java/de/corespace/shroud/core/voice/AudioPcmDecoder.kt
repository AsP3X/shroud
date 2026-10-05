package de.corespace.shroud.core.voice

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Mono float PCM (−1…1) at [sampleRate] Hz. */
class DecodedAudio(val samples: FloatArray, val sampleRate: Int) {
    /** Seconds of audio (used as the transcript scorer's duration, media-voice-links §9.8). */
    val durationSeconds: Double get() = if (sampleRate > 0) samples.size.toDouble() / sampleRate else 0.0

    override fun toString(): String = "DecodedAudio(samples=${samples.size}, sampleRate=$sampleRate)"
}

/**
 * Voice-note audio → PCM for on-device transcription, and the true length of a note — iOS
 * `AVAudioFile` decode (`ios/shroud/Services/Voice/VoiceTranscriber.swift:311-316`) and
 * `AVAudioPlayer(data:).duration` (`VoiceMessageBubble.swift:694-708`) on Android
 * (media-voice-links §9.8, §13.2; conversation-thread §11.1). Everything runs from memory: a
 * [MediaDataSource] over the decrypted bytes, never a plaintext temp file (plan §1.1 rule 7).
 *
 * Handles every container a note arrives in: `audio/mp4` AAC (iPhone, Android), `audio/wav` 16-bit
 * mono (web; parsed here directly, web `voice/wav.ts:94-130`) and `audio/webm` Opus (web fallback).
 * Blocking; call off the main thread. Never logs content.
 */
object AudioPcmDecoder {
    /** Whisper's input rate. */
    const val WHISPER_SAMPLE_RATE = 16_000

    /** Longer notes are transcribed up to here (a 30-minute note is ≈ 115 MB of floats at 16 kHz, §9.8). */
    const val MAX_SECONDS = 30 * 60

    /**
     * [bytes] decoded, downmixed to mono and resampled to 16 kHz ([resample]) — the input of the
     * Whisper engine (W3-TRANSCRIPTION). [mimeHint] is the payload's `mime`; the
     * container is sniffed either way. Throws [IOException] when nothing can be decoded.
     */
    fun decodeMono16k(bytes: ByteArray, mimeHint: String? = null): FloatArray {
        val decoded = decodeMono(bytes, mimeHint)
        return resample(decoded.samples, decoded.sampleRate.toDouble(), WHISPER_SAMPLE_RATE.toDouble())
    }

    /** [bytes] decoded to mono at their own rate, at most [maxSeconds] of them. Throws [IOException]. */
    fun decodeMono(bytes: ByteArray, mimeHint: String? = null, maxSeconds: Int = MAX_SECONDS): DecodedAudio {
        if (looksLikeWav(bytes) || mimeHint?.substringBefore(';')?.trim()?.lowercase() in WAV_MIMES) {
            parseWav(bytes)?.let { return capped(it, maxSeconds) }
        }
        return decodeWithCodec(bytes, maxSeconds)
    }

    /**
     * The note's length in milliseconds measured from the container, or null when it cannot be read —
     * for payloads whose `d` is missing or tiny (`d < 300`, conversation-thread §11.1). Blocking.
     */
    fun durationMs(bytes: ByteArray): Int? {
        parseWav(bytes)?.let { return (it.durationSeconds * 1000).toInt().takeIf { ms -> ms > 0 } }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(ByteArrayMediaDataSource(bytes))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.takeIf { it > 0 }?.let { min(it, Int.MAX_VALUE.toLong()).toInt() }
        } catch (_: RuntimeException) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Nothing left to free.
            }
        }
    }

    /**
     * Band-limited resampling with a windowed-sinc filter, the web's `resample` (`voice/wav.ts`)
     * line for line. Plain interpolation from 44.1 or 48 kHz to 16 kHz folds everything above 8 kHz
     * (the hiss of s, sh, f) back into the band Whisper hears; this filter removes it first (80 dB
     * down). Each output is divided by its weight sum, so the edges keep their level.
     * `count = max(1, Int(n / ratio))`. The same array when the rates match or it is empty.
     */
    fun resample(samples: FloatArray, fromRate: Double, toRate: Double): FloatArray {
        if (samples.isEmpty() || fromRate == toRate) return samples
        val step = fromRate / toRate
        val count = max(1, (samples.size / step).toInt())
        // Kernel units per input sample: below one when downsampling, which widens the kernel and
        // lowers its cutoff to the output's Nyquist rate.
        val scale = min(1.0, toRate / fromRate) * CUTOFF
        val reach = ZERO_CROSSINGS / scale
        val last = samples.size - 1
        return FloatArray(count) { i ->
            // Upsampling ends up to one input sample past the last one; hold it there.
            val center = min(i * step, last.toDouble())
            val from = max(0, ceil(center - reach).toInt())
            val to = min(last, floor(center + reach).toInt())
            var acc = 0.0
            var weights = 0.0
            for (k in from..to) {
                val at = abs(k - center) * scale * TABLE_STEPS
                val j = at.toInt()
                val weight = KERNEL[j] + (KERNEL[j + 1] - KERNEL[j]) * (at - j)
                acc += samples[k] * weight
                weights += weight
            }
            if (weights != 0.0) (acc / weights).toFloat() else 0f
        }
    }

    /** Sinc zero crossings on each side of an output sample. */
    private const val ZERO_CROSSINGS = 16

    /** Cutoff as a share of the lower Nyquist rate, so the filter has stopped before it. */
    private const val CUTOFF = 0.9

    /** Kernel table steps per zero crossing; read with linear interpolation. */
    private const val TABLE_STEPS = 128

    /** Blackman-windowed sinc over [0, ZERO_CROSSINGS], one entry per step and one spare. */
    private val KERNEL = FloatArray(ZERO_CROSSINGS * TABLE_STEPS + 2).also { table ->
        for (i in 0..ZERO_CROSSINGS * TABLE_STEPS) {
            val x = i.toDouble() / TABLE_STEPS
            val sinc = if (i == 0) 1.0 else sin(PI * x) / (PI * x)
            val u = x / ZERO_CROSSINGS
            table[i] = (sinc * (0.42 + 0.5 * cos(PI * u) + 0.08 * cos(2 * PI * u))).toFloat()
        }
    }

    /** Interleaved frames → mono by averaging the channels (§9.8). The same array for one channel. */
    fun downmix(interleaved: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        return FloatArray(frames) { f ->
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            sum / channels
        }
    }

    /**
     * 16-bit mono PCM WAV, what the web client sends (web `pcmFromWav`, `voice/wav.ts:94-130`): RIFF/WAVE
     * chunks walked to `fmt ` (PCM, format 1) and `data`; null for anything else (other bit depths or
     * channel counts go through the platform decoder). Samples scale as the web's: `s / 0x8000` below
     * zero, `s / 0x7fff` above.
     */
    fun parseWav(bytes: ByteArray): DecodedAudio? {
        if (bytes.size < 44 || !looksLikeWav(bytes)) return null
        var offset = 12
        var sampleRate = 0
        var bits = 0
        var channels = 0
        var dataOffset = -1
        var dataLength = 0L
        while (offset + 8 <= bytes.size) {
            val id = ascii(bytes, offset, 4)
            val size = u32(bytes, offset + 4)
            val start = offset + 8
            if (id == "fmt ") {
                if (size < 16 || start + 16 > bytes.size) return null
                if (u16(bytes, start) != 1) return null
                channels = u16(bytes, start + 2)
                sampleRate = u32(bytes, start + 4).toInt()
                bits = u16(bytes, start + 14)
            } else if (id == "data") {
                dataOffset = start
                dataLength = size
                break
            }
            val next = start.toLong() + size + (size % 2)
            if (next > Int.MAX_VALUE) return null
            offset = next.toInt()
        }
        if (dataOffset < 0 || bits != 16 || channels != 1 || sampleRate <= 0) return null
        val count = min(dataLength / 2, ((bytes.size - dataOffset) / 2).toLong()).toInt()
        if (count <= 0) return null
        val samples = FloatArray(count) { i ->
            val p = dataOffset + i * 2
            val s = ((bytes[p].toInt() and 0xFF) or (bytes[p + 1].toInt() shl 8)).toShort().toInt()
            if (s < 0) s / 32768f else s / 32767f
        }
        return DecodedAudio(samples, sampleRate)
    }

    private fun decodeWithCodec(bytes: ByteArray, maxSeconds: Int): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(ByteArrayMediaDataSource(bytes))
            } catch (e: Exception) {
                throw IOException("unreadable audio", e)
            }
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("no audio track")
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            val decoder = try {
                MediaCodec.createDecoderByType(mime).also { codec = it }
            } catch (e: Exception) {
                throw IOException("no decoder", e)
            }
            decoder.configure(format, null, null, 0)
            decoder.start()

            val out = FloatBuilder()
            var limit = maxSamples(sampleRate, maxSeconds)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleRounds = 0
            while (!outputDone && out.size < limit) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = checkNotNull(decoder.getInputBuffer(inIndex))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, max(0L, extractor.sampleTime), 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val changed = decoder.outputFormat
                        sampleRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = changed.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            changed.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        limit = maxSamples(sampleRate, maxSeconds)
                    }
                    outIndex >= 0 -> {
                        idleRounds = 0
                        val buffer = checkNotNull(decoder.getOutputBuffer(outIndex))
                        if (info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            appendMono(buffer.slice().order(ByteOrder.nativeOrder()), pcmFloat, max(1, channels), out)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone -> {
                        if (++idleRounds > MAX_IDLE_ROUNDS) outputDone = true
                    }
                }
            }
            if (out.size == 0 || sampleRate <= 0) throw IOException("no audio decoded")
            return DecodedAudio(out.toArray(min(out.size, limit)), sampleRate)
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException("audio decode failed", e)
        } finally {
            codec?.let {
                try {
                    it.stop()
                } catch (_: RuntimeException) {
                    // Not started or already failed.
                }
                it.release()
            }
            extractor.release()
        }
    }

    private fun appendMono(buffer: java.nio.ByteBuffer, pcmFloat: Boolean, channels: Int, out: FloatBuilder) {
        if (pcmFloat) {
            val floats = buffer.asFloatBuffer()
            val frames = floats.remaining() / channels
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) sum += floats.get(f * channels + c)
                out.add(sum / channels)
            }
        } else {
            val shorts = buffer.asShortBuffer()
            val frames = shorts.remaining() / channels
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) sum += shorts.get(f * channels + c) / 32768f
                out.add(sum / channels)
            }
        }
    }

    private fun capped(audio: DecodedAudio, maxSeconds: Int): DecodedAudio {
        val limit = maxSamples(audio.sampleRate, maxSeconds)
        return if (audio.samples.size <= limit) audio else DecodedAudio(audio.samples.copyOf(limit), audio.sampleRate)
    }

    private fun maxSamples(sampleRate: Int, maxSeconds: Int): Int =
        min(Int.MAX_VALUE.toLong(), max(1L, sampleRate.toLong()) * max(1, maxSeconds)).toInt()

    private fun looksLikeWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE"

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(CharArray(length) { (bytes[offset + it].toInt() and 0xFF).toChar() })

    private fun u16(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long = (u16(bytes, offset).toLong()) or (u16(bytes, offset + 2).toLong() shl 16)

    private val WAV_MIMES = setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_ROUNDS = 100

    /** A growing float array (a 10-minute note is ≈ 26 M samples at 44.1 kHz). */
    private class FloatBuilder {
        private var data = FloatArray(1 shl 16)
        var size = 0
            private set

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(min(Int.MAX_VALUE - 8L, data.size * 2L).toInt())
            data[size++] = value
        }

        fun toArray(length: Int): FloatArray = data.copyOf(length)
    }
}

/** A [MediaDataSource] over bytes in memory, for `MediaExtractor` and `MediaMetadataRetriever` (API 23). */
class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        if (size <= 0) return 0
        val count = min(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() = Unit
}
