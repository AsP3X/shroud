package de.corespace.shroud.core.net

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.ForwardingSource
import okio.Source
import okio.buffer

/**
 * Transfer progress, 0…1, for media uploads and downloads — the port of
 * `TransferProgressObserver` (`TransferProgressObserver.swift:3-79`, api-realtime §2.8).
 *
 * iOS samples the task's byte counters every 100 ms; Android counts the bytes as they pass
 * instead and applies the same throttle: a report needs [MIN_INTERVAL_NANOS] since the last one
 * **and** a step of at least [MIN_STEP] (`:19-21, 72`), except the final `1.0`, which is always
 * reported, once. Reports never go backwards — OkHttp may write a request body a second time when
 * it retries on a fresh connection, and the ring must not jump back. A transfer of unknown length
 * (chunked, `total <= 0`) reports nothing, and the UI shows its indeterminate look (`:67-68`).
 *
 * The callback runs on the thread doing the I/O (OkHttp's / `Dispatchers.IO`); the caller decides
 * where to post it.
 */
internal class ProgressThrottle(
    private val total: Long,
    private val onProgress: (Double) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var lastFraction = -1.0
    private var lastReportNanos = 0L
    private var finished = false

    /** [done] bytes of [total] have passed (an absolute count, not a delta). */
    @Synchronized
    fun update(done: Long) {
        if (total <= 0 || finished) return
        val fraction = (done.toDouble() / total).coerceIn(0.0, 1.0)
        if (fraction >= 1.0) {
            finished = true
            lastFraction = 1.0
            onProgress(1.0)
            return
        }
        val now = nanoTime()
        val first = lastFraction < 0
        if (fraction - lastFraction.coerceAtLeast(0.0) < MIN_STEP) return
        if (!first && now - lastReportNanos < MIN_INTERVAL_NANOS) return
        lastFraction = fraction
        lastReportNanos = now
        onProgress(fraction)
    }

    companion object {
        /** `TransferProgressObserver.sampleInterval` (`:17-19`). */
        const val MIN_INTERVAL_NANOS = 100_000_000L

        /** `TransferProgressObserver.reportThreshold` (`:20-21`). */
        const val MIN_STEP = 0.005
    }
}

/**
 * A request body that reports how much of [delegate] has left the device. Its length must be known
 * (the server compares it with the upload's `size_bytes`, `media.rs` put_content).
 */
class ProgressRequestBody(
    private val delegate: RequestBody,
    onProgress: (Double) -> Unit,
) : RequestBody() {
    private val throttle = ProgressThrottle(delegate.contentLength(), onProgress)

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isOneShot(): Boolean = delegate.isOneShot()

    override fun writeTo(sink: BufferedSink) {
        val counting = object : ForwardingSink(sink) {
            private var written = 0L

            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                throttle.update(written)
            }
        }
        val buffered = counting.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}

/** A response body source that reports how much of [total] bytes has arrived. */
class ProgressSource(
    delegate: Source,
    total: Long,
    onProgress: (Double) -> Unit,
) : ForwardingSource(delegate) {
    private val throttle = ProgressThrottle(total, onProgress)
    private var read = 0L

    override fun read(sink: Buffer, byteCount: Long): Long {
        val count = super.read(sink, byteCount)
        if (count > 0) {
            read += count
            throttle.update(read)
        }
        return count
    }
}
