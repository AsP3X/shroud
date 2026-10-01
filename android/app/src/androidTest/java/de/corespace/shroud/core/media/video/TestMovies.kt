package de.corespace.shroud.core.media.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4OrientationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.container.XmpData
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Small MP4 fixtures written on the device — iOS's `makeMovie` / `taggedMovie`
 * (`VideoMediaEncodeTests.swift:292-325`, `MediaMetadataScrubberTests.swift:248-284`): frames
 * encoded with `MediaCodec` (moving stripes, so the encoder has something to do), optional AAC
 * sound (a 440 Hz tone), muxed by Media3's `Mp4Muxer`, which also writes the rotation and the
 * identifying metadata a phone camera leaves in a clip.
 */
@OptIn(UnstableApi::class)
internal object TestMovies {
    data class Spec(
        val seconds: Double,
        val width: Int,
        val height: Int,
        val fps: Int = 30,
        val audio: Boolean = false,
        val videoMime: String = MimeTypes.VIDEO_H264,
        /** Track rotation (tkhd matrix): 90 makes a landscape-coded clip display as portrait. */
        val rotation: Int = 0,
        /** Location, device and capture date, like `taggedMovie`. */
        val tagged: Boolean = false,
    )

    /** Strings a scrubbed output must not contain (`MediaMetadataScrubberTests.swift:304`). */
    val LEAKS = listOf("+52.5200", "Test Phone", "2026-09-01")

    /** 2026-09-01T10:00:00Z, the capture time of a tagged clip (MP4 epoch seconds in `mvhd`). */
    val TAGGED_CREATION_MP4_SECONDS: Long =
        Mp4TimestampData.unixTimeToMp4TimeSeconds(Instant.parse("2026-09-01T10:00:00Z").toEpochMilli())

    fun hasEncoder(mime: String): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

    fun write(file: File, spec: Spec): File {
        val video = encodeVideo(spec)
        val audio = if (spec.audio) encodeAudio(spec.seconds) else null
        FileOutputStream(file).use { stream ->
            val muxer = Mp4Muxer.Builder(SeekableMuxerOutput.of(stream)).build()
            try {
                val videoTrack = muxer.addTrack(video.format)
                val audioTrack = audio?.let { muxer.addTrack(it.format) }
                muxer.addMetadataEntry(Mp4OrientationData(spec.rotation))
                if (spec.tagged) tags().forEach(muxer::addMetadataEntry)
                val samples = video.samples.map { videoTrack to it } + (audio?.samples.orEmpty().map { audioTrack!! to it })
                for ((track, sample) in samples.sortedBy { it.second.ptsUs }) {
                    val buffer = ByteBuffer.allocateDirect(sample.data.size).put(sample.data)
                    buffer.flip()
                    muxer.writeSampleData(track, buffer, BufferInfo(sample.ptsUs, sample.data.size, sample.flags))
                }
            } finally {
                muxer.close()
            }
        }
        return file
    }

    /** What iOS's `taggedMovie` writes: ISO 6709 location, make, model, creation date (+ XMP). */
    private fun tags(): List<Metadata.Entry> = listOf(
        Mp4LocationData(52.52f, 13.405f),
        Mp4TimestampData(TAGGED_CREATION_MP4_SECONDS, TAGGED_CREATION_MP4_SECONDS),
        mdta("com.apple.quicktime.location.ISO6709", "+52.5200+013.4050+034.000/"),
        mdta("com.apple.quicktime.make", "Apple"),
        mdta("com.apple.quicktime.model", "Test Phone"),
        mdta("com.apple.quicktime.creationdate", "2026-09-01T12:00:00+0200"),
        XmpData(
            ("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                "<rdf:Description xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmlns:tiff=\"http://ns.adobe.com/tiff/1.0/\" " +
                "xmp:CreateDate=\"2026-09-01T12:00:00+02:00\" tiff:Model=\"Test Phone\"/></rdf:RDF></x:xmpmeta>").toByteArray(),
        ),
    )

    private fun mdta(key: String, value: String) = MdtaMetadataEntry(key, value.toByteArray(), MdtaMetadataEntry.TYPE_INDICATOR_STRING)

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private class Track(val format: Format, val samples: List<Sample>)

    private fun encodeVideo(spec: Spec): Track {
        val codec = MediaCodec.createEncoderByType(spec.videoMime)
        val format = MediaFormat.createVideoFormat(spec.videoMime, spec.width, spec.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, maxOf(400_000, spec.width * spec.height * 3))
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val frames = (spec.seconds * spec.fps).roundToInt()
        try {
            return drain(codec, frames) { index, frame ->
                val image = checkNotNull(codec.getInputImage(index))
                fillFrame(image, spec.width, spec.height, frame)
                spec.width * spec.height * 3 / 2 to frame * 1_000_000L / spec.fps
            }.let { (outFormat, samples) ->
                Track(
                    MediaFormatUtil.createFormatFromMediaFormat(outFormat).buildUpon()
                        .setFrameRate(spec.fps.toFloat())
                        .build(),
                    samples,
                )
            }
        } finally {
            codec.stop()
            codec.release()
        }
    }

    /** Moving vertical stripes in luma, flat grey chroma; row-wise bulk writes keep 1080p fast. */
    private fun fillFrame(image: android.media.Image, width: Int, height: Int, frame: Int) {
        val y = image.planes[0]
        val row = ByteArray(width) { x -> (if (((x + frame * 8) / 32) % 2 == 0) 40 else 200).toByte() }
        val alt = ByteArray(width) { x -> (if (((x + frame * 8) / 32 + 1) % 2 == 0) 40 else 200).toByte() }
        val yBuf = y.buffer
        for (r in 0 until height) {
            yBuf.position(r * y.rowStride)
            if (y.pixelStride == 1) {
                yBuf.put(if ((r / 32) % 2 == 0) row else alt)
            } else {
                val src = if ((r / 32) % 2 == 0) row else alt
                for (x in 0 until width) yBuf.put(r * y.rowStride + x * y.pixelStride, src[x])
            }
        }
        val chromaWidth = width / 2
        for (p in 1..2) {
            val plane = image.planes[p]
            val buf = plane.buffer
            val span = (chromaWidth - 1) * plane.pixelStride + 1
            val grey = ByteArray(span) { 128.toByte() }
            for (r in 0 until height / 2) {
                val start = r * plane.rowStride
                if (start + span > buf.capacity()) break
                buf.position(start)
                buf.put(grey)
            }
        }
    }

    private fun encodeAudio(seconds: Double): Track {
        val codec = MediaCodec.createEncoderByType(MimeTypes.AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MimeTypes.AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_SAMPLES * 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val totalSamples = (seconds * SAMPLE_RATE).roundToInt()
        val chunks = (totalSamples + CHUNK_SAMPLES - 1) / CHUNK_SAMPLES
        try {
            val (outFormat, samples) = drain(codec, chunks) { index, chunk ->
                val buffer = checkNotNull(codec.getInputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                buffer.clear()
                val first = chunk * CHUNK_SAMPLES
                val count = minOf(CHUNK_SAMPLES, totalSamples - first)
                for (i in 0 until count) {
                    val t = (first + i).toDouble() / SAMPLE_RATE
                    buffer.putShort((sin(2 * PI * 440 * t) * 8_000).toInt().toShort())
                }
                count * 2 to first * 1_000_000L / SAMPLE_RATE
            }
            return Track(MediaFormatUtil.createFormatFromMediaFormat(outFormat), samples)
        } finally {
            codec.stop()
            codec.release()
        }
    }

    /**
     * Feeds [inputs] buffers through [fill] (returns size and presentation time), then end of
     * stream, and collects the encoded samples (codec config travels in the output format).
     */
    private fun drain(codec: MediaCodec, inputs: Int, fill: (index: Int, n: Int) -> Pair<Int, Long>): Pair<MediaFormat, List<Sample>> {
        val info = MediaCodec.BufferInfo()
        val samples = ArrayList<Sample>()
        var outFormat: MediaFormat? = null
        var queued = 0
        var eosQueued = false
        var lastPts = 0L
        val deadline = System.nanoTime() + 120_000_000_000L
        while (true) {
            check(System.nanoTime() < deadline) { "encoder stalled" }
            if (!eosQueued) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    if (queued < inputs) {
                        val (size, pts) = fill(index, queued)
                        codec.queueInputBuffer(index, 0, size, pts, 0)
                        lastPts = pts
                        queued++
                    } else {
                        codec.queueInputBuffer(index, 0, 0, lastPts + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                out >= 0 -> {
                    val buffer = checkNotNull(codec.getOutputBuffer(out))
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(data)
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        samples += Sample(data, info.presentationTimeUs, if (key) C.BUFFER_FLAG_KEY_FRAME else 0)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        return checkNotNull(outFormat) { "no output format" } to samples
    }

    private const val SAMPLE_RATE = 44_100
    private const val CHUNK_SAMPLES = 1024
    private const val TIMEOUT_US = 10_000L
}
