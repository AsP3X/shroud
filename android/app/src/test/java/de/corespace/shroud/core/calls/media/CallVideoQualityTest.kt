package de.corespace.shroud.core.calls.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * The camera's ladder: where a call starts, when it steps down and up, and what the encoder is
 * told. The same cases as the web's `videoQuality.selftest.ts` and the iPhone's
 * `CallVideoQualityTests` (docs/calls.md, "Camera quality").
 */
class CallVideoQualityTest {
    private val good = CameraSample(estimate = 6_000_000.0, limitation = QualityLimitation.NONE, loss = 0.0)
    private val none = CameraSample(estimate = null, limitation = null, loss = null)

    /** Feeds [sample] [times] times; the rung names it passed through. */
    private fun feed(quality: CameraQuality, sample: CameraSample, times: Int): List<String> {
        val seen = ArrayList<String>()
        repeat(times) { if (quality.sample(sample)) seen += quality.rung.name }
        return seen
    }

    @Test
    fun whereACallStarts() {
        assertEquals("a 1080p camera starts at 720p", "720p", CameraQuality(1920).rung.name)
        assertEquals("a 720p camera starts at 720p", "720p", CameraQuality(1280).rung.name)
        assertEquals("a small camera starts at its own size", "360p", CameraQuality(640).rung.name)
        assertEquals("an unknown camera starts at 720p", "720p", CameraQuality(null).rung.name)
        assertEquals("the ceiling is the camera's size", 5, ceilingFor(1920))
        assertEquals(4, ceilingFor(1280))
        assertEquals(4, ceilingFor(1300))
        assertEquals("a capture a little under 1080p still reaches it", 5, ceilingFor(1880))
    }

    @Test
    fun cleanReadingsStepUpToTheTop() {
        val quality = CameraQuality(1920)
        assertTrue("the first three readings settle", feed(quality, good, 3).isEmpty())
        assertTrue("three clean readings are not yet enough", feed(quality, good, 3).isEmpty())
        assertEquals("the fourth goes up to 1080p", listOf("1080p"), feed(quality, good, 1))
        assertTrue("1080p is the top", feed(quality, good, 20).isEmpty())
    }

    @Test
    fun aCameraNeverGoesAboveItsOwnSize() {
        val quality = CameraQuality(1280)
        assertTrue("a 720p camera never goes above 720p", feed(quality, good, 30).isEmpty())
    }

    @Test
    fun goingUpDoesNotWaitForTheEstimate() {
        // A camera sending less than the link could carry: the estimate stays near what it sends.
        val sending = CameraQuality(1920)
        val low = CameraSample(estimate = 400_000.0, limitation = QualityLimitation.NONE, loss = 0.0)
        assertEquals("going up does not wait for the estimate", "1080p", feed(sending, low, 7).firstOrNull())
        val bare = CameraQuality(1920)
        assertEquals(
            "without an estimate or a limitation, clean readings still go up",
            "1080p",
            feed(bare, none, 7).firstOrNull(),
        )
    }

    @Test
    fun stepsUpOnlyWithAnIdleEncoderAndALowLoss() {
        val quality = CameraQuality(1920)
        assertTrue("a busy processor does not go up", feed(quality, good.copy(limitation = QualityLimitation.CPU), 30).isEmpty())
        assertTrue(
            "an encoder short of bits does not go up",
            feed(quality, good.copy(limitation = QualityLimitation.BANDWIDTH), 30).isEmpty(),
        )
        assertEquals("nor down while the estimate has room", "720p", quality.rung.name)
        assertTrue("a lossy link does not go up", feed(quality, good.copy(loss = 0.05), 30).isEmpty())
        quality.sample(good)
        quality.sample(good)
        quality.sample(good.copy(loss = 0.05))
        assertTrue("a bad reading starts the count again", feed(quality, good, 3).isEmpty())
        assertEquals("four clean ones in a row go up", listOf("1080p"), feed(quality, good, 1))
    }

    private val tight = CameraSample(estimate = 500_000.0, limitation = QualityLimitation.BANDWIDTH, loss = 0.0)

    @Test
    fun aStarvedEncoderStepsDownAsFarAsTheEstimateNeedsAtOnce() {
        val quality = CameraQuality(1920)
        feed(quality, good, 3)
        assertTrue("one starved reading is not enough", feed(quality, tight, 1).isEmpty())
        assertEquals("two go down as far as the estimate needs at once", listOf("360p"), feed(quality, tight, 1))
        assertTrue("and stay where the estimate fits", feed(quality, tight, 10).isEmpty())
    }

    @Test
    fun aLowEstimateAloneDoesNotStepDown() {
        val quality = CameraQuality(1920)
        feed(quality, good, 3)
        assertTrue("a low estimate alone does not step down", feed(quality, tight.copy(limitation = QualityLimitation.NONE), 2).isEmpty())
        assertEquals("the encoder is not short of bits", "720p", quality.rung.name)
    }

    @Test
    fun starvedReadingsMustComeInARow() {
        val quality = CameraQuality(1920)
        feed(quality, good, 3)
        quality.sample(tight)
        quality.sample(good)
        assertTrue("starved readings must come in a row", feed(quality, tight, 1).isEmpty())
    }

    @Test
    fun lossStepsDownOneRungAtATime() {
        val quality = CameraQuality(1920)
        feed(quality, good, 3)
        val lossy = good.copy(loss = 0.15)
        assertEquals("loss steps down one rung", "540p", feed(quality, lossy, 2).firstOrNull())
        assertTrue("and settles before the next", feed(quality, lossy, 2).isEmpty())
        assertEquals("then steps again", "360p", feed(quality, lossy, 2).firstOrNull())
    }

    @Test
    fun aStarvedLinkGoesToTheBottom() {
        val quality = CameraQuality(1920)
        feed(quality, good, 3)
        val starved = CameraSample(estimate = 40_000.0, limitation = QualityLimitation.BANDWIDTH, loss = 0.5)
        assertEquals("a starved link goes to the bottom", "180p", feed(quality, starved, 2).firstOrNull())
        assertTrue("the bottom is the bottom", feed(quality, starved, 20).isEmpty())
        assertEquals("and stays there", 0, quality.rungIndex)
    }

    @Test
    fun anUpgradeThatDoesNotHoldWaitsLongerNextTime() {
        val quality = CameraQuality(1920)
        feed(quality, good, 7)
        assertEquals("up to 1080p", "1080p", quality.rung.name)
        val short = CameraSample(estimate = 1_500_000.0, limitation = QualityLimitation.BANDWIDTH, loss = 0.0)
        feed(quality, short, 2 + 2)
        assertEquals("the link could not carry it", "720p", quality.rung.name)
        assertTrue("the next try waits twice as long", feed(quality, good, 2 + 7).isEmpty())
        assertEquals("eight clean readings", listOf("1080p"), feed(quality, good, 1))
        feed(quality, good, 16)
        feed(quality, short, 2)
        assertEquals("down again after it held", "720p", quality.rung.name)
        assertTrue("a held upgrade resets the wait", feed(quality, good, 2 + 3).isEmpty())
        assertEquals("a held upgrade resets the wait", listOf("1080p"), feed(quality, good, 1))
    }

    @Test
    fun theCamerasSizeSetsTheCeiling() {
        val quality = CameraQuality(1920)
        feed(quality, good, 7)
        assertTrue("a smaller camera brings the rung down to it", quality.setCapture(1280))
        assertEquals("720p", quality.rung.name)
        assertFalse("a larger one only lifts the ceiling", quality.setCapture(1920))
        assertEquals("720p", quality.rung.name)
        assertFalse("an unknown size changes nothing", quality.setCapture(0))
        assertFalse(quality.setCapture(null))
    }

    @Test
    fun aPauseStartsTheCountAgain() {
        val quality = CameraQuality(1920)
        feed(quality, good, 6)
        quality.pause()
        assertTrue("after a pause the count starts again", feed(quality, good, 5).isEmpty())
        assertEquals("and finishes", listOf("1080p"), feed(quality, good, 1))
    }

    @Test
    fun aPauseBeforeTheFirstReadingKeepsTheOpeningSettle() {
        // A voice call whose camera comes on later: the ladder was paused from the start.
        val quality = CameraQuality(1920)
        quality.pause()
        quality.pause()
        assertTrue("a pause before the first reading keeps the opening settle", feed(quality, good, 6).isEmpty())
        assertEquals("seven readings in", listOf("1080p"), feed(quality, good, 1))
    }

    @Test
    fun whatTheEncoderIsTold() {
        val at720 = cameraEncoding(CAMERA_LADDER[4], width = 1920, height = 1080)
        assertEquals("720p from a 1080p camera", CameraEncoding(2_200_000, 30, 1.5), at720)
        val portrait = cameraEncoding(CAMERA_LADDER[4], width = 1080, height = 1920)
        assertEquals("a portrait camera too", 1.5, portrait.scaleResolutionDownBy, 0.0)
        val small = cameraEncoding(CAMERA_LADDER[5], width = 1280, height = 720)
        assertEquals("never enlarged", 1.0, small.scaleResolutionDownBy, 0.0)
        assertEquals("an unknown size is sent as it is", 1.0, cameraEncoding(CAMERA_LADDER[0]).scaleResolutionDownBy, 0.0)
        val tile = cameraEncoding(tileOf(CAMERA_LADDER[4]), width = 1920, height = 1080)
        assertEquals("the tile while sharing", CameraEncoding(350_000, 15, 3.0), tile)
        assertEquals("the tile from the top rung is the tile", CAMERA_TILE, tileOf(CAMERA_LADDER[5]))
        val low = cameraEncoding(tileOf(CAMERA_LADDER[1]), width = 1920, height = 1080)
        assertEquals("a camera below the tile stays below it", CameraEncoding(300_000, 15, 4.0), low)
    }

    @Test
    fun readingTheStats() {
        val report = listOf(
            StatRow("OT1", "outbound-rtp", mapOf("kind" to "video", "qualityLimitationReason" to "bandwidth")),
            StatRow("RI1", "remote-inbound-rtp", mapOf("kind" to "video", "fractionLost" to 0.0625)),
            StatRow("T1", "transport", mapOf("selectedCandidatePairId" to "CP2")),
            StatRow("CP1", "candidate-pair", mapOf("nominated" to false, "availableOutgoingBitrate" to 99.0)),
            StatRow(
                "CP2",
                "candidate-pair",
                mapOf("nominated" to true, "state" to "succeeded", "availableOutgoingBitrate" to 1_234_567.0),
            ),
        )
        val sample = readCameraSample(report)!!
        assertEquals("the selected pair's estimate", 1_234_567.0, sample.estimate!!, 0.0)
        assertEquals("the encoder's limitation", QualityLimitation.BANDWIDTH, sample.limitation)
        assertEquals("the reported loss", 0.0625, sample.loss!!, 0.0)

        val bare = readCameraSample(listOf(StatRow("OT1", "outbound-rtp", mapOf("kind" to "video"))))!!
        assertNull("a report without those stats", bare.estimate)
        assertNull(bare.limitation)
        assertNull(bare.loss)

        val nominated = readCameraSample(report.filter { it.type != "transport" })!!
        assertEquals("without a transport, the nominated pair", 1_234_567.0, nominated.estimate!!, 0.0)
        assertNull("nothing sent yet: no reading", readCameraSample(report.filter { it.type != "outbound-rtp" }))
    }

    @Test
    fun statsNumbersComeInWhateverBoxTheyArrive() {
        // WebRTC's Java stats carry uint64 as BigInteger, int64 as Long, double as Double.
        val pair = { value: Any? ->
            listOf(
                StatRow("OT", "outbound-rtp", mapOf("kind" to "video")),
                StatRow("CP", "candidate-pair", mapOf("nominated" to true, "state" to "succeeded", "availableOutgoingBitrate" to value)),
            )
        }
        assertEquals(2_000_000.0, readCameraSample(pair(2_000_000L))!!.estimate!!, 0.0)
        assertEquals(2_000_000.0, readCameraSample(pair(BigInteger.valueOf(2_000_000)))!!.estimate!!, 0.0)
        assertNull(readCameraSample(pair(Double.NaN))!!.estimate)
        assertNull(readCameraSample(pair("2000000"))!!.estimate)
        // The audio sender's rows never count for the camera.
        val audio = readCameraSample(
            listOf(
                StatRow("OT", "outbound-rtp", mapOf("kind" to "video")),
                StatRow("OA", "outbound-rtp", mapOf("kind" to "audio", "qualityLimitationReason" to "cpu")),
                StatRow("RA", "remote-inbound-rtp", mapOf("kind" to "audio", "fractionLost" to 0.5)),
            ),
        )!!
        assertNull(audio.limitation)
        assertNull(audio.loss)
        assertNull("an audio sender alone is no reading", readCameraSample(listOf(StatRow("OA", "outbound-rtp", mapOf("kind" to "audio")))))
    }
}
