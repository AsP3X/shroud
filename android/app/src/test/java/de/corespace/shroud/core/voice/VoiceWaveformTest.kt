package de.corespace.shroud.core.voice

import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.net.wire.MediaMessagePayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.math.abs

/**
 * Ports `VoiceWaveformTests` (`ios/shroudTests/VoiceWaveformTests.swift:7-220`; media-voice-links
 * §8.2, §12.7) — capture → downsample → payload encode/decode → render-time resample — plus the web
 * placeholder vectors (`web/src/crypto/mediaPayload.ts:160-176`, D13) and the bubble's sample rule
 * (`VoiceMessageBubble.swift:168-178`). Level normalisation lives in [VoiceLevelTest].
 */
class VoiceWaveformTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun unsigned(b: ByteArray) = b.map { it.toInt() and 0xFF }

    // MARK: - Downsample (VoiceWaveformTests.swift:8-62)

    /** The regression the iOS suite exists for: a varied envelope must never flatten (`:10-29`). */
    @Test
    fun downsampleKeepsVariationFromAVariedEnvelope() {
        val envelope = FloatArray(200) { it / 199f }
        val buckets = unsigned(VoiceWaveform.downsample(envelope, 44))
        assertEquals(44, buckets.size)
        val low = buckets.first()
        val high = buckets.last()
        assertTrue(high > low)
        assertTrue(high - low > 100)
        assertTrue("a ramp must not collapse to a handful of values", buckets.toSet().size > 10)
    }

    @Test
    fun downsampleReturnsEmptyForAnEmptyEnvelope() {
        assertEquals(0, VoiceWaveform.downsample(FloatArray(0), 44).size)
        assertEquals(0, VoiceWaveform.downsample(floatArrayOf(0.5f, 0.5f), 0).size)
    }

    @Test
    fun downsampleAveragesWithinEachBucket() {
        val envelope = FloatArray(100) { if (it < 50) 0f else 1f }
        val buckets = unsigned(VoiceWaveform.downsample(envelope, 2))
        assertEquals(listOf(8, 255), buckets)
    }

    @Test
    fun downsampleFloorsSilenceInsteadOfProducingGaps() {
        val buckets = unsigned(VoiceWaveform.downsample(FloatArray(100), 12))
        assertEquals(12, buckets.size)
        assertTrue(buckets.all { it == 8 })
    }

    @Test
    fun downsampleHandlesFewerSamplesThanBuckets() {
        assertEquals(16, VoiceWaveform.downsample(floatArrayOf(0.2f, 0.9f), 16).size)
    }

    /**
     * iOS truncates (`UInt8(Float)`), the web rounds (`Math.round`): the web gives [26, 96, 153] for this
     * envelope (computed by running `downsampleEnvelope`), iOS and Android [25, 95, 153].
     */
    @Test
    fun downsampleTruncatesLikeIosAndStaysWithinOneOfTheWeb() {
        val envelope = floatArrayOf(0.1f, 0.5f, 0.25f, 0.9f, 0.3f)
        val android = unsigned(VoiceWaveform.downsample(envelope, 3))
        assertEquals(listOf(25, 95, 153), android)
        val web = listOf(26, 96, 153)
        android.zip(web).forEach { (a, w) -> assertTrue(abs(a - w) <= 1) }
    }

    // MARK: - Usability of stored envelopes (`:64-80`)

    @Test
    fun flatStoredWaveformIsRejectedSoTheBubbleFallsBack() {
        assertFalse(VoiceWaveform.isUsable(ByteArray(44) { 24 }))
        assertFalse(VoiceWaveform.isUsable(ByteArray(0)))
        assertFalse(VoiceWaveform.isUsable(bytes(100, 103, 99, 102)))
    }

    @Test
    fun variedStoredWaveformIsUsed() {
        assertTrue(VoiceWaveform.isUsable(bytes(8, 90, 200, 40)))
        val envelope = FloatArray(120) { (it % 40) / 39f }
        assertTrue(VoiceWaveform.isUsable(VoiceWaveform.downsample(envelope, 44)))
    }

    /** Bytes above 127 are unsigned: 8…255 spans 247, not a negative number. */
    @Test
    fun isUsableReadsBytesUnsigned() {
        assertTrue(VoiceWaveform.isUsable(bytes(8, 255)))
        assertFalse(VoiceWaveform.isUsable(bytes(250, 255)))
        assertTrue(VoiceWaveform.isUsable(Bytes.of(bytes(8, 200))))
        assertFalse(VoiceWaveform.isUsable(null as Bytes?))
    }

    // MARK: - Payload round trip (`:82-113`)

    @Test
    fun encodeDecodeRoundTripsExactly() {
        val original = bytes(8, 40, 120, 200, 255, 17)
        val encoded = VoiceWaveform.encode(original)
        assertNotNull("expected base64 for a non-empty waveform", encoded)
        encoded!!
        assertArrayEquals(original, VoiceWaveform.decode(encoded))
        // The same Base64 the web's encodeWaveform writes for these bytes.
        assertEquals("CCh4yP8R", encoded)
    }

    @Test
    fun encodeReturnsNilForEmptySoThePayloadOmitsTheField() {
        assertNull(VoiceWaveform.encode(ByteArray(0)))
    }

    @Test
    fun decodeRejectsMissingOrGarbageValues() {
        assertNull(VoiceWaveform.decode(null))
        assertNull(VoiceWaveform.decode(""))
        assertNull(VoiceWaveform.decode("not base64!!"))
    }

    /** Swift `Data(base64Encoded:)` wants padding; so does the strict decoder. */
    @Test
    fun decodeIsStrictAboutPadding() {
        assertNull(VoiceWaveform.decode("CCh4yP8"))
        assertArrayEquals(bytes(8, 40), VoiceWaveform.decode("CCg="))
    }

    @Test
    fun normalizedMapsBytesToUnitRange() {
        val values = VoiceWaveform.normalized(bytes(0, 128, 255))
        assertEquals(0f, values[0], 0f)
        assertTrue(abs(values[1] - 0.502f) < 0.01f)
        assertEquals(1f, values[2], 0f)
    }

    // MARK: - Render-time resample (`:115-140`)

    @Test
    fun resampleHitsTheRequestedBarCount() {
        val samples = FloatArray(44) { it / 43f }
        assertEquals(18, VoiceWaveform.resample(samples, 18).size)
        assertEquals(38, VoiceWaveform.resample(samples, 38).size)
        assertArrayEquals(samples, VoiceWaveform.resample(samples, 44), 0f)
        assertSame(samples, VoiceWaveform.resample(samples, 44))
    }

    @Test
    fun resamplePreservesTheOverallShape() {
        val samples = FloatArray(44) { it / 43f }
        val reduced = VoiceWaveform.resample(samples, 11)
        assertTrue(reduced.last() > reduced.first())
    }

    @Test
    fun resampleOfEmptySamplesStillFillsTheBars() {
        val filled = VoiceWaveform.resample(FloatArray(0), 20)
        assertEquals(20, filled.size)
        assertTrue(filled.all { it == 0.1f })
        assertEquals(0, VoiceWaveform.resample(floatArrayOf(0.5f), 0).size)
    }

    /** Same values as the web's `resampleWaveform` for these inputs (computed by running it). */
    @Test
    fun resampleMatchesTheWebAveragesAndRepeats() {
        val samples = floatArrayOf(0.1f, 0.5f, 0.25f, 0.9f, 0.3f)
        assertArrayEquals(floatArrayOf(0.1f, 0.375f, 0.6f), VoiceWaveform.resample(samples, 3), 1e-6f)
        assertArrayEquals(
            floatArrayOf(0.1f, 0.1f, 0.5f, 0.25f, 0.25f, 0.9f, 0.3f),
            VoiceWaveform.resample(samples, 7),
            1e-6f,
        )
    }

    // MARK: - Placeholder (`:142-156`; web FNV-1a version, D13)

    @Test
    fun placeholderIsStableForTheSameMessage() {
        val id = UUID.randomUUID()
        assertArrayEquals(VoiceWaveform.placeholder(id, 30), VoiceWaveform.placeholder(id, 30), 0f)
    }

    @Test
    fun placeholderIsVariedAndInRange() {
        val samples = VoiceWaveform.placeholder(UUID.randomUUID(), 40)
        assertEquals(40, samples.size)
        assertTrue(samples.all { it in 0f..1f })
        assertTrue("placeholder must read as speech, not a flat line", samples.toSet().size > 5)
    }

    /** Byte-for-byte the web's `placeholderWaveform(id, 12)` (computed by running `mediaPayload.ts`). */
    @Test
    fun placeholderMatchesTheWebClient() {
        val vectors = mapOf(
            "00000000-0000-0000-0000-000000000000" to doubleArrayOf(
                0.5321525000000001, 0.579255702930261, 0.6000036570271436, 0.570412451626217, 0.5599155213398991, 0.6422945284181524,
                0.6297024698602451, 0.787325740364354, 0.7158082134630627, 0.3783588870484512, 0.40430816325568175, 0.5106750000000001,
            ),
            "3f2504e0-4f89-41d3-9a0c-0305e82c3301" to doubleArrayOf(
                0.27412000000000003, 0.6272732191388157, 0.7199885226652151, 0.4470463506737418, 0.9445606481817266, 0.458340977311334,
                0.6242276617915898, 0.8168732398199676, 0.7202141456399367, 0.6536696151125356, 0.5167212632167945, 0.39028,
            ),
            "c56a4180-65aa-42ec-a945-5fd21dec0538" to doubleArrayOf(
                0.3397625, 0.3979802735227712, 0.5851691645482547, 0.6081076491394732, 0.7741348923930979, 0.983375071095378,
                0.5327983670450461, 0.728758375371977, 0.785324032253743, 0.4939806666633203, 0.570322211542623, 0.33764500000000003,
            ),
        )
        for ((id, expected) in vectors) {
            val fromUuid = VoiceWaveform.placeholder(UUID.fromString(id), 12)
            val fromString = VoiceWaveform.placeholder(id, 12)
            assertArrayEquals(fromString, fromUuid, 0f)
            for (i in expected.indices) assertEquals("$id[$i]", expected[i].toFloat(), fromUuid[i], 0f)
        }
    }

    /** The id hashes as the web's lower-case string, whatever case the UUID was parsed from. */
    @Test
    fun placeholderHashesTheLowerCaseId() {
        val upper = UUID.fromString("C56A4180-65AA-42EC-A945-5FD21DEC0538")
        assertArrayEquals(VoiceWaveform.placeholder("c56a4180-65aa-42ec-a945-5fd21dec0538", 12), VoiceWaveform.placeholder(upper, 12), 0f)
        assertEquals(0, VoiceWaveform.placeholder(upper, 0).size)
    }

    // MARK: - Bubble samples (`VoiceMessageBubble.swift:168-178`)

    @Test
    fun bubbleUsesTheStoredEnvelopeWhenUsableElseThePlaceholder() {
        val id = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        val stored = Bytes.of(bytes(8, 90, 200, 40))
        assertArrayEquals(
            VoiceWaveform.resample(VoiceWaveform.normalized(stored), 32),
            VoiceWaveform.bubbleSamples(id, stored, 32),
            0f,
        )
        val flat = Bytes.of(ByteArray(44) { 24 })
        assertArrayEquals(VoiceWaveform.placeholder(id, 32), VoiceWaveform.bubbleSamples(id, flat, 32), 0f)
        assertArrayEquals(VoiceWaveform.placeholder(id, 32), VoiceWaveform.bubbleSamples(id, null, 32), 0f)
    }

    // MARK: - Sealed payload compatibility (`:158-194`)

    @Test
    fun voicePayloadCarriesTheWaveformThroughJSON() {
        val waveform = bytes(8, 64, 200, 255, 31)
        val payload = MediaMessagePayload(
            t = MediaMessagePayload.KIND_VOICE,
            mime = "audio/mp4",
            w = 0,
            h = 0,
            k = "a2V5",
            c = null,
            d = 4200,
            wf = VoiceWaveform.encode(waveform),
        )
        val decoded = MediaMessagePayload.parse(payload.encoded())
        assertNotNull(decoded)
        decoded!!
        assertTrue(decoded.isVoice)
        assertEquals(4200, decoded.d)
        assertArrayEquals(waveform, VoiceWaveform.decode(decoded.wf))
    }

    /** Old-client backtest: voice payloads sealed before `wf` existed must still decode. */
    @Test
    fun legacyVoicePayloadWithoutWaveformStillDecodes() {
        val legacy = """{"t":"voice","mime":"audio/mp4","w":0,"h":0,"k":"a2V5","d":1}"""
        val decoded = MediaMessagePayload.parse(legacy.toByteArray(Charsets.UTF_8))!!
        assertTrue(decoded.isVoice)
        assertNull(decoded.wf)
        assertNull(VoiceWaveform.decode(decoded.wf))
    }
}
