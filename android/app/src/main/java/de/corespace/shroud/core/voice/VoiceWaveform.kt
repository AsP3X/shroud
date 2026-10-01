package de.corespace.shroud.core.voice

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.Ids
import java.util.UUID
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The voice-message amplitude envelope, end to end: capture → [downsample] → payload [encode] /
 * [decode] (`wf`, MediaMessagePayload) → render-time [resample] — iOS `VoiceWaveform`
 * (`ios/shroud/ShroudUI/Components/VoiceWaveformView.swift:69-170`), web `crypto/mediaPayload.ts:97-176`
 * (media-voice-links §8.2, conversation-thread §11.9, plan C24). Pure; used by [VoiceRecorder] for the
 * payload and by the bubble and recording bar (W3) for the bars. The composable that draws them is
 * `VoiceWaveformView` (W3-THREAD-BUBBLES).
 *
 * Bytes are unsigned 0…255 on the wire; Kotlin's signed [Byte] is read with `and 0xFF` throughout.
 */
object VoiceWaveform {
    /** iOS floors a bucket at 8 so a silent stretch reads as a quiet line, not a gap (`VoiceWaveformView.swift:116-117`). */
    const val SILENCE_FLOOR = 8

    /** A stored envelope must span at least this much to count as real data (`VoiceWaveformView.swift:92-95`). */
    const val MINIMUM_SPAN = 8

    /** [resample] fills this when there are no samples at all (`VoiceWaveformView.swift:126`). */
    const val EMPTY_SAMPLE = 0.1f

    /**
     * Payload encoding (`VoiceWaveformView.swift:71-74`): standard padded Base64 of the raw bytes, null
     * for an empty array so the payload omits `wf`.
     */
    fun encode(buckets: ByteArray): String? = if (buckets.isEmpty()) null else B64.encode(buckets)

    /**
     * Payload decoding (`VoiceWaveformView.swift:76-79`): Swift `Data(base64Encoded:)` semantics
     * ([B64.decodeStrict]); null for null, empty, invalid or empty data — messages sealed before `wf`
     * existed decode as null rather than failing.
     */
    fun decode(base64: String?): ByteArray? {
        if (base64.isNullOrEmpty()) return null
        val data = B64.decodeStrict(base64) ?: return null
        return data.takeIf { it.isNotEmpty() }
    }

    /** Bytes → 0…1 (`VoiceWaveformView.swift:81-83`). */
    fun normalized(buckets: ByteArray): FloatArray = FloatArray(buckets.size) { (buckets[it].toInt() and 0xFF) / 255f }

    /** [normalized] of a model's stored waveform. */
    fun normalized(buckets: Bytes): FloatArray = normalized(buckets.toByteArray())

    /**
     * Whether a stored envelope carries real amplitude information (`VoiceWaveformView.swift:85-95`):
     * non-empty and `max − min ≥ 8`. An earlier iOS build sealed a constant envelope into every voice
     * message (its recorder cleared the samples before downsampling them); those payloads cannot be
     * rewritten, so a flat array counts as missing and the bubble draws the placeholder instead.
     */
    fun isUsable(buckets: ByteArray): Boolean {
        if (buckets.isEmpty()) return false
        var low = 255
        var high = 0
        for (b in buckets) {
            val v = b.toInt() and 0xFF
            if (v < low) low = v
            if (v > high) high = v
        }
        return high - low >= MINIMUM_SPAN
    }

    /** [isUsable] of a model's stored waveform. */
    fun isUsable(buckets: Bytes?): Boolean = buckets != null && isUsable(buckets.toByteArray())

    /**
     * Averages a captured envelope (0…1 per sample) into [buckets] bytes of 0…255
     * (`VoiceWaveformView.swift:97-120`). Empty for an empty envelope or `buckets <= 0`, so the caller
     * omits `wf` and the bubble falls back to its placeholder instead of a flat line.
     *
     * Bucket `b` averages `envelope[floor(b·stride) ..< max(start + 1, floor((b + 1)·stride))]` (clamped
     * to the envelope), `stride = n / buckets`, then `UInt8(max(8, min(255, mean · 255)))`. Swift's
     * `UInt8(Float)` truncates; the web rounds (`mediaPayload.ts:117`) — this follows iOS, so a value can
     * differ from a web-recorded note by 1.
     */
    fun downsample(envelope: FloatArray, buckets: Int): ByteArray {
        if (buckets <= 0 || envelope.isEmpty()) return ByteArray(0)
        val n = envelope.size
        val out = ByteArray(buckets)
        val stride = n.toDouble() / buckets.toDouble()
        for (bucket in 0 until buckets) {
            val start = (bucket.toDouble() * stride).toInt()
            val end = max(start + 1, ((bucket + 1).toDouble() * stride).toInt())
            val from = min(start, n - 1)
            val to = min(end, n)
            var sum = 0f
            for (i in from until to) sum += envelope[i]
            val mean = if (to > from) sum / (to - from).toFloat() else 0f
            out[bucket] = max(SILENCE_FLOOR.toFloat(), min(255f, mean * 255f)).toInt().toByte()
        }
        return out
    }

    /** [downsample] of a list (the recorder's envelope). */
    fun downsample(envelope: List<Float>, buckets: Int): ByteArray = downsample(envelope.toFloatArray(), buckets)

    /**
     * Resamples to exactly [count] bars so a bubble can size itself by duration
     * (`VoiceWaveformView.swift:122-135`): `[]` for `count <= 0`; the same array when the size already
     * matches; [EMPTY_SAMPLE] × count for no samples; else bucket means with `end = max(start + 1, …)`
     * (an empty slice repeats `samples[min(start, n − 1)]`).
     */
    fun resample(samples: FloatArray, count: Int): FloatArray {
        if (count <= 0) return FloatArray(0)
        if (samples.size == count) return samples
        if (samples.isEmpty()) return FloatArray(count) { EMPTY_SAMPLE }
        val n = samples.size
        return FloatArray(count) { index ->
            val start = (index.toDouble() * n.toDouble() / count.toDouble()).toInt()
            val end = max(start + 1, ((index + 1).toDouble() * n.toDouble() / count.toDouble()).toInt())
            val from = min(start, n - 1)
            val to = min(end, n)
            if (to <= from) {
                samples[from]
            } else {
                var sum = 0f
                for (i in from until to) sum += samples[i]
                sum / (to - from).toFloat()
            }
        }
    }

    /**
     * Stand-in envelope for notes sealed before waveforms existed, or with a flat one
     * (`VoiceWaveformView.swift:137-151`): a tapered pseudo-random shape, `sin(pos·π)·0.45 + 0.55`
     * times `0.45 + jitter·0.55`, clamped to 0.12…1.
     *
     * iOS seeds SplitMix64 from the per-launch `hashValue`, stable only within a launch; this is the
     * web's FNV-1a version over the lower-case id (`mediaPayload.ts:160-176`, media-voice-links D13):
     * stable across launches and the same bars the web client draws for the same note.
     */
    fun placeholder(id: UUID, count: Int): FloatArray = placeholder(Ids.wire(id), count)

    /** [placeholder] over the id's wire string (web `placeholderWaveform(id, count)`). */
    fun placeholder(id: String, count: Int): FloatArray {
        if (count <= 0) return FloatArray(0)
        var hash = FNV_OFFSET
        for (ch in id) {
            hash = hash xor ch.code
            hash *= FNV_PRIME
        }
        val out = FloatArray(count)
        val span = max(count - 1, 1).toDouble()
        for (index in 0 until count) {
            val position = index.toDouble() / span
            val envelope = sin(position * PI) * 0.45 + 0.55
            hash = (hash xor (hash ushr 16)) * MIX_1
            hash = (hash xor (hash ushr 15)) * MIX_2
            val jitter = ((hash.toLong() and 0xFFFF_FFFFL) % 1000L).toDouble() / 1000.0
            out[index] = min(1.0, max(0.12, envelope * (0.45 + jitter * 0.55))).toFloat()
        }
        return out
    }

    /**
     * The bars a voice bubble draws (`VoiceMessageBubble.swift:168-178`): the stored envelope when it
     * [isUsable], else the [placeholder] for the message, resampled to [barCount].
     */
    fun bubbleSamples(id: UUID, stored: Bytes?, barCount: Int): FloatArray {
        val source = if (stored != null && isUsable(stored)) normalized(stored) else placeholder(id, barCount)
        return resample(source, barCount)
    }

    // 32-bit FNV-1a and the murmur-style mixer of the web placeholder; Kotlin Int arithmetic wraps
    // like `Math.imul`.
    private const val FNV_OFFSET = 0x811c9dc5.toInt()
    private const val FNV_PRIME = 0x01000193
    private const val MIX_1 = 0x7feb352d
    private const val MIX_2 = 0x846ca68b.toInt()
}
