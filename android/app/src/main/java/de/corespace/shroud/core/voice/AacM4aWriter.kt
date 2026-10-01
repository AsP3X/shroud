package de.corespace.shroud.core.voice

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.min

/**
 * AAC-LC in an MPEG-4 audio file, from 16-bit mono PCM — the encoding half of iOS's
 * `AVAudioRecorder(settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, 44 100 Hz, 1 channel])`
 * (`ios/shroud/Services/Voice/VoiceRecorder.swift:82-92`; media-voice-links §8.1, D8): a synchronous
 * `MediaCodec` encoder (`audio/mp4a-latm`, `AACObjectLC`, [VoiceFormat.AAC_BITRATE]) feeding a
 * `MediaMuxer` (`MUXER_OUTPUT_MPEG_4`). The muxer writes no location (only `setLocation` would), but
 * its MPEG-4 writer adds device keys (`com.android.version`) in a movie-level `meta` box, so [finish]
 * blanks every `meta`/`udta` box afterwards ([Mp4MetadataBlanker]). iPhone (`AVAudioPlayer`) and the
 * web (`<audio>`, `audio/mp4`) play the result.
 *
 * [output] is a `SensitiveTempFiles` file (`cacheDir/shroud-voice-*.m4a`, plaintext for the length
 * of the take, plan §1.1 rule 7); [VoiceRecorder] deletes it after reading it or on cancel.
 *
 * One thread at a time (the recording loop, then `finish` after the loop has ended). Construction
 * throws [IOException] when the device has no AAC encoder or the file cannot be opened.
 */
class AacM4aWriter(private val output: File) : VoiceEncoder {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var samplesWritten = 0
    private var framesQueued = 0L
    private var released = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, VoiceFormat.SAMPLE_RATE, VoiceFormat.CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, VoiceFormat.AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }
        val encoder = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (e: Exception) {
            throw IOException("no AAC encoder", e)
        }
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
        } catch (e: Exception) {
            encoder.release()
            throw IOException("AAC encoder did not start", e)
        }
        codec = encoder
        muxer = try {
            MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            releaseCodec()
            throw IOException("could not open the voice file", e)
        }
    }

    override fun write(pcm: ShortArray, offset: Int, size: Int) {
        check(!released) { "writer released" }
        var position = offset
        val end = offset + size
        while (position < end) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(endOfStream = false)
                continue
            }
            val buffer = checkNotNull(codec.getInputBuffer(index)) { "no input buffer" }
            buffer.clear()
            val frames = min(end - position, buffer.remaining() / BYTES_PER_FRAME)
            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, position, frames)
            codec.queueInputBuffer(index, 0, frames * BYTES_PER_FRAME, presentationUs(framesQueued), 0)
            framesQueued += frames
            position += frames
            drain(endOfStream = false)
        }
    }

    override fun finish() {
        check(!released) { "writer released" }
        try {
            val deadline = SystemClock.elapsedRealtime() + DRAIN_TIMEOUT_MS
            var index = codec.dequeueInputBuffer(TIMEOUT_US)
            while (index < 0) {
                if (SystemClock.elapsedRealtime() > deadline) throw IOException("encoder stalled")
                drain(endOfStream = false)
                index = codec.dequeueInputBuffer(TIMEOUT_US)
            }
            codec.queueInputBuffer(index, 0, 0, presentationUs(framesQueued), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(endOfStream = true)
            if (!muxerStarted || samplesWritten == 0) throw IOException("no audio was encoded")
            muxer.stop()
        } catch (e: IOException) {
            abort()
            throw e
        } catch (e: RuntimeException) {
            abort()
            throw IOException("could not finish the voice file", e)
        }
        releaseAll()
        // After release: the muxer has closed the file. Throws IOException when the file cannot be
        // checked; the take then fails instead of sending device metadata.
        Mp4MetadataBlanker.blank(output)
    }

    override fun abort() {
        if (released) return
        releaseAll()
    }

    /** Moves every finished AAC frame into the muxer; with [endOfStream], waits for the end flag. */
    private fun drain(endOfStream: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + DRAIN_TIMEOUT_MS
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (SystemClock.elapsedRealtime() > deadline) throw IOException("encoder did not finish")
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) throw IOException("encoder format changed twice")
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val data = checkNotNull(codec.getOutputBuffer(index)) { "no output buffer" }
                    // The codec config (csd-0) already went to the muxer with the output format.
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && muxerStarted) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        muxer.writeSampleData(track, data, info)
                        samplesWritten++
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                // INFO_OUTPUT_BUFFERS_CHANGED (deprecated) and others: keep going.
            }
        }
    }

    private fun releaseAll() {
        released = true
        releaseCodec()
        try {
            muxer.release()
        } catch (_: RuntimeException) {
            // A started muxer that was never stopped complains on release; the file is deleted anyway.
        }
    }

    private fun releaseCodec() {
        try {
            codec.stop()
        } catch (_: RuntimeException) {
            // Not started, or already in an error state.
        }
        try {
            codec.release()
        } catch (_: RuntimeException) {
            // Nothing more to free.
        }
    }

    private fun presentationUs(frames: Long): Long = frames * 1_000_000L / VoiceFormat.SAMPLE_RATE

    private companion object {
        const val BYTES_PER_FRAME = 2 * VoiceFormat.CHANNELS
        const val MAX_INPUT_BYTES = 16 * 1024
        const val TIMEOUT_US = 10_000L
        const val DRAIN_TIMEOUT_MS = 3_000L
    }
}
