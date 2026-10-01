package de.corespace.shroud.core.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.blackholeSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The progress throttle of `TransferProgressObserver` (`TransferProgressObserver.swift:17-21,
 * 56-78`; api-realtime §2.8): 100 ms and 0.5 % between reports, the final 1.0 always and once,
 * nothing for an unknown length, never backwards.
 */
class ProgressBodiesTest {
    private var now = 0L
    private val reports = mutableListOf<Double>()
    private fun throttle(total: Long) = ProgressThrottle(total, { reports += it }, nanoTime = { now })

    private fun advanceMillis(ms: Long) {
        now += ms * 1_000_000
    }

    @Test
    fun reportsNeedAHundredMillisecondsAndHalfAPercent() {
        val throttle = throttle(1_000)
        throttle.update(4)                 // 0.4 %: below the step
        throttle.update(10)                // 1 %: the first report
        throttle.update(50)                // 5 %, but only 0 ms later
        advanceMillis(99)
        throttle.update(60)                // 99 ms: still too soon
        advanceMillis(1)
        throttle.update(62)                // 100 ms and +5.2 %
        advanceMillis(500)
        throttle.update(64)                // +0.2 %: too small a step
        throttle.update(1_000)             // the end is always reported
        assertEquals(listOf(0.01, 0.062, 1.0), reports)
    }

    @Test
    fun theFinalReportComesOnceAndNothingAfterIt() {
        val throttle = throttle(100)
        throttle.update(100)
        throttle.update(100)
        advanceMillis(1_000)
        throttle.update(50)
        assertEquals(listOf(1.0), reports)
    }

    @Test
    fun anUnknownLengthReportsNothing() {
        val chunked = throttle(-1)
        chunked.update(10)
        chunked.update(1_000_000)
        val empty = throttle(0)
        empty.update(0)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun aRetriedBodyNeverGoesBackwards() {
        // OkHttp may write a request body twice (a retry on a fresh connection).
        val payload = ByteArray(1 shl 20) { it.toByte() }
        val body = ProgressRequestBody(payload.toRequestBody("application/octet-stream".toMediaType()), onProgress = { reports += it })
        assertEquals(payload.size.toLong(), body.contentLength())
        assertEquals("application/octet-stream", body.contentType().toString())
        val sink = blackholeSink().buffer()
        body.writeTo(sink)
        val afterFirst = reports.toList()
        body.writeTo(sink)
        assertEquals("the second write reports nothing new", afterFirst, reports)
        assertEquals(1.0, reports.last(), 0.0)
        reports.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", b > a) }
    }

    @Test
    fun theSourceCountsWhatArrives() {
        val payload = Buffer().write(ByteArray(10_000))
        val source = ProgressSource(payload, 10_000, onProgress = { reports += it }).buffer()
        val out = Buffer()
        while (source.read(out, 1_000) != -1L) Unit
        assertEquals(10_000L, out.size)
        assertEquals(1.0, reports.last(), 0.0)
        reports.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", b > a) }
    }
}
