package de.corespace.shroud.core.voice

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A microphone that delivers [totalFrames] frames of [sample] (frame index → value) and then nothing,
 * so a take's length is exact. [delivered] opens once every frame was handed out.
 */
class FakePcmSource(
    private val totalFrames: Int,
    private val sample: (Int) -> Short = { 0 },
    private val failStart: Boolean = false,
    private val startGate: CountDownLatch? = null,
    private val failReadAfter: Int? = null,
) : PcmSource {
    val delivered = CountDownLatch(1)
    val released = CountDownLatch(1)

    @Volatile var started = false

    @Volatile var stopped = false
    private var position = 0

    override fun start() {
        startGate?.await(5, TimeUnit.SECONDS)
        if (failStart) throw IOException("microphone busy")
        started = true
    }

    override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
        if (failReadAfter != null && position >= failReadAfter) return -3
        val n = minOf(size, totalFrames - position)
        if (n <= 0) {
            delivered.countDown()
            Thread.sleep(1)
            return 0
        }
        for (i in 0 until n) buffer[offset + i] = sample(position + i)
        position += n
        if (position >= totalFrames) delivered.countDown()
        return n
    }

    override fun stop() {
        stopped = true
    }

    override fun release() {
        released.countDown()
    }
}

/** An encoder that writes the raw PCM into the file, little-endian; [finish] can be made to fail. */
class FakeVoiceEncoder(
    private val output: File,
    private val failFinish: Boolean = false,
    private val writesNothing: Boolean = false,
) : VoiceEncoder {
    val framesWritten = AtomicInteger()

    @Volatile var finished = false

    @Volatile var aborted = false

    override fun write(pcm: ShortArray, offset: Int, size: Int) {
        framesWritten.addAndGet(size)
        if (writesNothing) return
        val bytes = ByteArray(size * 2)
        for (i in 0 until size) {
            val v = pcm[offset + i].toInt()
            bytes[i * 2] = v.toByte()
            bytes[i * 2 + 1] = (v shr 8).toByte()
        }
        output.appendBytes(bytes)
    }

    override fun finish() {
        if (failFinish) throw IOException("no audio was encoded")
        finished = true
    }

    override fun abort() {
        aborted = true
    }
}

/** Hands out the given source and records the encoders it built. */
class FakeCapture(
    private val source: () -> PcmSource,
    private val encoder: (File) -> FakeVoiceEncoder = { FakeVoiceEncoder(it) },
) : VoiceCaptureFactory {
    val encoders = ArrayList<FakeVoiceEncoder>()
    val sources = ArrayList<PcmSource>()

    override fun openSource(): PcmSource = source().also { synchronized(sources) { sources += it } }

    override fun openEncoder(output: File): VoiceEncoder = encoder(output).also { synchronized(encoders) { encoders += it } }
}

/** Audio focus that grants (or refuses) and lets a test take it away. */
class FakeFocusPort(var grant: Boolean = true) : AudioFocusCoordinator.FocusPort {
    var requests = 0
    var abandons = 0
    var held = false
    private var onLoss: (() -> Unit)? = null

    override fun request(onLoss: () -> Unit): Boolean {
        requests++
        held = grant
        this.onLoss = if (grant) onLoss else null
        return grant
    }

    override fun abandon() {
        abandons++
        held = false
    }

    /** A call rings: the system takes focus away. */
    fun loseFocus() {
        onLoss?.invoke()
    }
}

/** A [VoicePlayer] driven by hand: the test says when the note is ready, ends or fails. */
class FakeVoicePlayer : VoicePlayer {
    override var listener: VoicePlayer.Listener? = null
    var loaded: ByteArray? = null
    var loads = 0
    var playing = false
    var currentSpeed = 1f
    var stops = 0
    override var positionMs: Long = 0L
    override val isAdvancing: Boolean get() = playing
    val seeks = ArrayList<Long>()

    override fun load(data: ByteArray) {
        loaded = data
        loads++
        playing = false
        positionMs = 0
    }

    override fun play() {
        playing = true
    }

    override fun pause() {
        playing = false
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        this.positionMs = positionMs
    }

    override fun setSpeed(rate: Float) {
        currentSpeed = rate
    }

    override fun stop() {
        stops++
        playing = false
        loaded = null
    }

    fun ready(durationMs: Long?) = listener!!.onReady(durationMs)

    fun end() = listener!!.onEnded()

    fun fail() = listener!!.onError()

    fun systemPause() {
        playing = false
        listener!!.onPausedBySystem()
    }
}

/** Polls [condition] for up to [timeoutMs] (the recording loop runs on a real IO thread). */
fun awaitCondition(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met within $timeoutMs ms")
        Thread.sleep(2)
    }
}
