package de.corespace.shroud.core.transcription

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * whisper.cpp speed and memory on this device: the P7 gate (00-plan §3) that decides which models
 * Android offers — base q5_1 if its real-time factor is ≤ 0.3 and the peak resident memory ≤ 400 MB;
 * small q5_1 only where its RTF is ≤ 1.0 and the phone has ≥ 6 GB RAM (media-voice-links §9.9).
 *
 * Each clip runs the way a voice note will (iOS `WhisperKitEngine.swift:41-100`): detect the language
 * on the opening, then one whole-note pass with it forced ([WhisperDecodeOptions.voiceNote]). The RTF
 * the gate reads is (detect + transcribe) / audio length; model loading is reported apart.
 *
 * Runs on any device with the model downloaded through [models]; `TranscriptionBenchmarkTest`
 * (androidTest) drives it with public-domain fixtures. Nothing is logged here — the caller decides
 * what to print, and [Report.lines] leaves transcripts out unless asked (they are user content when
 * the clips are).
 */
class TranscriptionBenchmark(
    private val context: Context,
    private val models: WhisperModelStore,
    private val whisperThread: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    /** 16 kHz mono audio to transcribe; [expectedLanguage] is only compared, never forced. */
    class Clip(val name: String, val pcm16k: FloatArray, val expectedLanguage: String? = null) {
        val seconds: Double get() = pcm16k.size / Pcm16k.SAMPLE_RATE.toDouble()
    }

    data class ClipResult(
        val clip: String,
        val audioSeconds: Double,
        val detected: WhisperLanguage?,
        val expectedLanguage: String?,
        val detectMs: Long,
        val transcribeMs: Long,
        val text: String,
        val confidence: Double,
        val timings: WhisperTimings?,
    ) {
        /** Real-time factor the gate reads: (detect + transcribe) / audio. */
        val rtf: Double get() = (detectMs + transcribeMs) / 1000.0 / audioSeconds

        /** The pass alone, as whisper.cpp's own benchmarks quote it. */
        val transcribeRtf: Double get() = transcribeMs / 1000.0 / audioSeconds
    }

    data class ModelResult(
        val model: WhisperModelFile,
        val threads: Int,
        val loadMs: Long,
        val rssBeforeLoadMb: Double?,
        val rssAfterLoadMb: Double?,
        /** Peak resident set of the process while this model ran (`VmHWM`, reset before the model when the kernel allows). */
        val peakRssMb: Double?,
        val peakWasReset: Boolean,
        val clips: List<ClipResult>,
    ) {
        val worstRtf: Double get() = clips.maxOfOrNull { it.rtf } ?: Double.NaN
    }

    data class Report(val device: DeviceProfile, val results: List<ModelResult>) {
        /**
         * The P7 verdict for [result] on this device: base needs RTF ≤ 0.3 and peak RSS ≤ 400 MB;
         * small needs RTF ≤ 1.0 and ≥ 6 GB RAM (00-plan §3 P7).
         */
        fun passesGate(result: ModelResult): Boolean {
            val rss = result.peakRssMb ?: return false
            return when (result.model) {
                WhisperModelFile.BaseQ5_1 -> result.worstRtf <= BASE_MAX_RTF && rss <= BASE_MAX_RSS_MB
                WhisperModelFile.SmallQ5_1 -> result.worstRtf <= SMALL_MAX_RTF && device.totalRamMb >= SMALL_MIN_RAM_MB
            }
        }

        fun lines(includeText: Boolean = false): List<String> = buildList {
            add(device.summary())
            for (result in results) {
                add(
                    "${result.model.fileName}: load %d ms, %d threads, RSS %s → %s after load, peak %s%s; worst RTF %.2f — P7 gate %s"
                        .format(
                            Locale.ROOT, result.loadMs, result.threads, mb(result.rssBeforeLoadMb), mb(result.rssAfterLoadMb),
                            mb(result.peakRssMb), if (result.peakWasReset) "" else " (process peak, not reset)",
                            result.worstRtf, if (passesGate(result)) "PASS" else "FAIL",
                        ),
                )
                for (clip in result.clips) {
                    val detected = clip.detected?.let { "%s %.2f".format(Locale.ROOT, it.code, it.probability) } ?: "none"
                    val expected = clip.expectedLanguage?.let { if (it == clip.detected?.code) " ✓" else " ✗ expected $it" } ?: ""
                    add(
                        "  %s (%.1f s): detect %d ms [%s%s], transcribe %d ms, RTF %.2f (pass %.2f), confidence %.2f%s"
                            .format(
                                Locale.ROOT, clip.clip, clip.audioSeconds, clip.detectMs, detected, expected, clip.transcribeMs,
                                clip.rtf, clip.transcribeRtf, clip.confidence,
                                clip.timings?.let { ", encode %.0f ms/window, decode %.1f ms/token".format(Locale.ROOT, it.encodeMs, it.decodeMs) } ?: "",
                            ),
                    )
                    if (includeText) add("    “${clip.text.take(TEXT_PREVIEW_CHARS)}${if (clip.text.length > TEXT_PREVIEW_CHARS) "…" else ""}”")
                }
            }
        }

        private fun mb(value: Double?) = value?.let { "%.0f MB".format(Locale.ROOT, it) } ?: "n/a"
    }

    /**
     * Downloads (when [download]) and loads each of [modelFiles] in turn and runs every clip on it.
     * Models are freed between runs, so their peaks don't add up.
     *
     * @throws WhisperModelException when a model is missing and [download] is false, or the download fails.
     * @throws WhisperException when whisper can't run here or rejects a model.
     */
    suspend fun run(
        modelFiles: List<WhisperModelFile>,
        clips: List<Clip>,
        threads: Int = WhisperContext.defaultThreads(),
        download: Boolean = true,
        onProgress: ((String) -> Unit)? = null,
    ): Report {
        val results = modelFiles.map { model ->
            val file = if (models.isInstalled(model)) {
                models.file(model)
            } else {
                if (!download) throw WhisperModelException("${model.fileName} is not downloaded")
                onProgress?.invoke("downloading ${model.fileName}")
                models.ensure(model)
            }
            onProgress?.invoke("running ${model.fileName}")
            withContext(whisperThread) { runModel(model, file, clips, threads) }
        }
        return Report(DeviceProfile.current(context), results)
    }

    private fun runModel(model: WhisperModelFile, file: File, clips: List<Clip>, threads: Int): ModelResult {
        System.gc()
        val peakReset = ProcessMemory.resetPeak()
        val rssBefore = ProcessMemory.rssMb()
        val loadStart = SystemClock.elapsedRealtime()
        WhisperContext.load(file).use { whisper ->
            val loadMs = SystemClock.elapsedRealtime() - loadStart
            val rssAfterLoad = ProcessMemory.rssMb()
            val clipResults = clips.map { clip ->
                whisper.resetTimings()
                val detectStart = SystemClock.elapsedRealtime()
                val detected = whisper.detectLanguage(clip.pcm16k, threads)
                val detectMs = SystemClock.elapsedRealtime() - detectStart
                val passStart = SystemClock.elapsedRealtime()
                val run = whisper.transcribe(clip.pcm16k, WhisperDecodeOptions.voiceNote(detected?.code, threads))
                val transcribeMs = SystemClock.elapsedRealtime() - passStart
                ClipResult(clip.name, clip.seconds, detected, clip.expectedLanguage, detectMs, transcribeMs, run.text, run.confidence, whisper.timings())
            }
            return ModelResult(model, threads, loadMs, rssBefore, rssAfterLoad, ProcessMemory.peakRssMb(), peakReset, clipResults)
        }
    }

    companion object {
        const val BASE_MAX_RTF = 0.3
        const val BASE_MAX_RSS_MB = 400.0
        const val SMALL_MAX_RTF = 1.0
        const val SMALL_MIN_RAM_MB = 6L * 1024
        private const val TEXT_PREVIEW_CHARS = 120
    }
}

/** What the benchmark ran on. */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val abi: String,
    val cores: Int,
    val totalRamMb: Long,
    val sdk: Int,
    val emulator: Boolean,
    val cpuBackend: String?,
    val systemInfo: String?,
) {
    fun summary(): String =
        "%s %s (%s%s), %d cores, %d MB RAM, Android API %d, %s, CPU backend %s | %s".format(
            Locale.ROOT, manufacturer, model, soc, if (emulator) ", EMULATOR" else "", cores, totalRamMb, sdk, abi,
            cpuBackend ?: "none", systemInfo ?: "",
        )

    companion object {
        fun current(context: Context): DeviceProfile {
            val memory = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
            val emulator = Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.PRODUCT.contains("sdk")
            return DeviceProfile(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                soc = soc,
                abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
                cores = Runtime.getRuntime().availableProcessors(),
                totalRamMb = memory.totalMem / (1024 * 1024),
                sdk = Build.VERSION.SDK_INT,
                emulator = emulator,
                cpuBackend = if (WhisperNative.isLoaded) WhisperNative.cpuBackend() else null,
                systemInfo = if (WhisperNative.isLoaded) WhisperNative.systemInfo() else null,
            )
        }
    }
}

/** Resident memory of this process from `/proc/self/status` (`VmRSS`, `VmHWM`). */
object ProcessMemory {
    fun rssMb(): Double? = statusKb("VmRSS")?.let { it / 1024.0 }

    fun peakRssMb(): Double? = statusKb("VmHWM")?.let { it / 1024.0 }

    /** Resets `VmHWM` to the current RSS (Linux 4.0+: `5` → `/proc/self/clear_refs`); false when refused. */
    fun resetPeak(): Boolean = try {
        File("/proc/self/clear_refs").writeText("5")
        true
    } catch (_: Exception) {
        false
    }

    internal fun statusKb(key: String, status: String? = readStatus()): Long? =
        status?.lineSequence()
            ?.firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')
            ?.trim()
            ?.substringBefore(' ')
            ?.toLongOrNull()

    private fun readStatus(): String? = try {
        File("/proc/self/status").readText()
    } catch (_: Exception) {
        null
    }
}

/**
 * 16 kHz mono float PCM for whisper from WAV files (PCM 16-bit, μ-law or 32-bit float, any rate and
 * channel count): channels averaged, rate changed by linear interpolation exactly as iOS and the web
 * do (`WhisperKitEngine.swift:236-249`, web `wav.ts:3-17`). For fixtures and the benchmark; voice
 * notes are decoded by `AudioPcmDecoder` (W2-VOICE, media §9.8).
 */
object Pcm16k {
    const val SAMPLE_RATE = 16_000

    class WavFormatException(message: String) : IllegalArgumentException(message)

    /** Decodes a RIFF/WAVE file. @throws WavFormatException when it is not one this reads. */
    fun fromWav(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") throw WavFormatException("not a RIFF/WAVE file")
        var format = -1
        var channels = 0
        var rate = 0
        var bits = 0
        var dataOffset = -1
        var dataSize = 0
        var at = 12
        while (at + 8 <= bytes.size) {
            val id = tag(bytes, at)
            val size = buffer.getInt(at + 4)
            if (size < 0) throw WavFormatException("bad chunk size")
            val body = at + 8
            when (id) {
                "fmt " -> {
                    if (size < 16 || body + 16 > bytes.size) throw WavFormatException("short fmt chunk")
                    format = buffer.getShort(body).toInt() and 0xFFFF
                    channels = buffer.getShort(body + 2).toInt() and 0xFFFF
                    rate = buffer.getInt(body + 4)
                    bits = buffer.getShort(body + 14).toInt() and 0xFFFF
                    if (format == FORMAT_EXTENSIBLE && size >= 26 && body + 26 <= bytes.size) {
                        format = buffer.getShort(body + 24).toInt() and 0xFFFF   // first two bytes of the sub-format GUID
                    }
                }
                "data" -> {
                    dataOffset = body
                    dataSize = min(size, bytes.size - body)
                }
            }
            at = body + size + (size and 1)   // chunks are padded to even sizes
        }
        if (format < 0) throw WavFormatException("no fmt chunk")
        if (dataOffset < 0) throw WavFormatException("no data chunk")
        if (channels <= 0 || rate <= 0) throw WavFormatException("bad format")
        val samples: FloatArray = when {
            format == FORMAT_PCM && bits == 16 -> FloatArray(dataSize / 2) { buffer.getShort(dataOffset + it * 2) / 32768f }
            format == FORMAT_MULAW && bits == 8 -> FloatArray(dataSize) { muLaw(bytes[dataOffset + it]) }
            format == FORMAT_FLOAT && bits == 32 -> FloatArray(dataSize / 4) { buffer.getFloat(dataOffset + it * 4) }
            else -> throw WavFormatException("unsupported WAV encoding $format/$bits")
        }
        return resample(downmix(samples, channels), rate.toDouble(), SAMPLE_RATE.toDouble())
    }

    /** Averages interleaved [channels] into one. */
    fun downmix(interleaved: FloatArray, channels: Int): FloatArray {
        if (channels == 1) return interleaved
        val frames = interleaved.size / channels
        return FloatArray(frames) { frame ->
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[frame * channels + c]
            sum / channels
        }
    }

    /** Linear interpolation, `count = max(1, Int(n / ratio))` (`WhisperKitEngine.swift:236-249`). */
    fun resample(samples: FloatArray, from: Double, to: Double): FloatArray {
        if (samples.isEmpty() || from == to) return samples
        val ratio = from / to
        val count = max(1, (samples.size / ratio).toInt())
        val last = samples.size - 1
        return FloatArray(count) { i ->
            val x = i * ratio
            val i0 = min(x.toInt(), last)
            val i1 = min(i0 + 1, last)
            val t = (x - i0).toFloat()
            samples[i0] * (1 - t) + samples[i1] * t
        }
    }

    /** The first [seconds] of [pcm]. */
    fun head(pcm: FloatArray, seconds: Double): FloatArray = pcm.copyOf(min(pcm.size, (seconds * SAMPLE_RATE).toInt()))

    /** [parts] one after another with [gapSeconds] of silence between them. */
    fun join(parts: List<FloatArray>, gapSeconds: Double): FloatArray {
        val gap = (gapSeconds * SAMPLE_RATE).toInt()
        val out = FloatArray(parts.sumOf { it.size } + gap * max(0, parts.size - 1))
        var at = 0
        parts.forEachIndexed { index, part ->
            if (index > 0) at += gap
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    /** G.711 μ-law byte → linear sample in [-1, 1]. */
    internal fun muLaw(encoded: Byte): Float {
        val u = encoded.toInt().inv() and 0xFF
        val sign = u and 0x80
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0F
        val magnitude = (((mantissa shl 3) + 0x84) shl exponent) - 0x84
        return (if (sign != 0) -magnitude else magnitude) / 32768f
    }

    private fun tag(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)

    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_MULAW = 7
    private const val FORMAT_EXTENSIBLE = 0xFFFE
}
