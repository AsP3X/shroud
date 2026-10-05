package de.corespace.shroud.core.transcription

import android.annotation.SuppressLint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A Whisper weights file the store can fetch and verify: name, exact size and SHA-256 (lower-case hex). */
interface WhisperModelSpec {
    val fileName: String
    val sizeBytes: Long
    val sha256: String
}

/**
 * The Whisper weights Android offers (media-voice-links §9.9; P7): ggml q5_1 files from the
 * whisper.cpp model repository, each pinned by size and SHA-256 (checked 2026-10-01 against
 * Hugging Face's `X-Linked-Size` / `X-Linked-ETag` and a full download).
 *
 * @property id the `TranscriptionModelId` raw value (`base`, `small`; iOS `TranscriptionModelID`,
 *   `TranscriptionTypes.swift:5-23`) under prefs `transcription.model` (W3-TRANSCRIPTION).
 */
enum class WhisperModelFile(
    val id: String,
    override val fileName: String,
    override val sizeBytes: Long,
    override val sha256: String,
) : WhisperModelSpec {
    /** The default (P7): fast on mid-range phones, the web's default size class. */
    BaseQ5_1("base", "ggml-base-q5_1.bin", 59_707_625L, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),

    /** Opt-in (P7), the size class of the iOS default; only where the benchmark gate allows it. */
    SmallQ5_1("small", "ggml-small-q5_1.bin", 190_085_487L, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
    ;

    companion object {
        val DEFAULT = BaseQ5_1

        fun forId(id: String?): WhisperModelFile? = entries.firstOrNull { it.id == id }
    }
}

/** A model could not be fetched or verified. The message names the step, never a token or path. */
class WhisperModelException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Whisper weights on disk: `noBackupFilesDir/whisper/<file>` (00-plan §1.5). Public weights, so not
 * sealed and **kept** across Log Out and removal (iOS keeps them too, `DeviceDataWipe.swift:225-232`;
 * the wipe's keep list, W2-AUTH-WIPE, must name the directory). The Android port of WhisperKit's download and
 * model folder (`WhisperKitEngine.swift:17-40, :145-173`).
 *
 * Downloads come from the pinned revision of `huggingface.co/ggerganov/whisper.cpp` over HTTPS with
 * a client of their own: no Shroud token, no cookies, redirects only within HTTPS (Hugging Face sends
 * the file from its CDN). Hugging Face sees the phone's address, as for iOS and the web. A download
 * resumes from `<file>.part` with `Range` after an interruption, is checked against the pinned size
 * and SHA-256 and only then renamed into place, so an installed file is always a verified one.
 *
 * Concurrent [ensure] calls for the same model share one download (the second waits and finds it
 * installed); W3-TRANSCRIPTION's `TranscriptionSession` adds its own single flight on top.
 *
 * @param baseUrl the directory URL the files are fetched from; tests point it at a MockWebServer.
 * @param usableSpace free bytes for the model directory; `TranscriptionModule` passes
 *   `StorageManager.getAllocatableBytes` (clearable caches count), tests fake a full disk, and the
 *   plain `File.usableSpace` default is the conservative fallback.
 * @param catalog every file this store manages; anything else in [directory] is pruned before a download.
 */
@SuppressLint("UsableSpace") // the default only; the app passes StorageManager.getAllocatableBytes
class WhisperModelStore(
    val directory: File,
    http: OkHttpClient,
    private val baseUrl: HttpUrl = PINNED_BASE_URL.toHttpUrl(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val usableSpace: (File) -> Long = { it.usableSpace },
    private val catalog: List<WhisperModelSpec> = WhisperModelFile.entries,
) {
    private val client: OkHttpClient = http.newBuilder()
        .followRedirects(true)
        .followSslRedirects(false)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private val locks: Map<String, Mutex> = catalog.associate { it.fileName to Mutex() }

    private fun lockOf(model: WhisperModelSpec): Mutex =
        requireNotNull(locks[model.fileName]) { "${model.fileName} is not in this store's catalog" }

    /** Where [model] lives once installed. */
    fun file(model: WhisperModelSpec): File = File(directory, model.fileName)

    /** True when [model] is downloaded and verified (a file of the pinned size; only verified files get this name). */
    fun isInstalled(model: WhisperModelSpec): Boolean = file(model).length() == model.sizeBytes

    /** Bytes on disk for [model]: the pinned size when installed, else what a resumed download keeps. */
    fun downloadedBytes(model: WhisperModelSpec): Long =
        if (isInstalled(model)) model.sizeBytes else partFile(model).length().coerceAtMost(model.sizeBytes)

    /**
     * Returns the installed file of [model], downloading it first when needed.
     *
     * Disk and network work runs on the IO dispatcher; callers may be on the main thread.
     *
     * @param progress fraction 0…1 of the file on disk (a resumed download starts above 0), at most
     *   once per 0.1 % from the download's thread, plus a final 1.0 from the caller's.
     * @throws WhisperModelException on a network or HTTP failure, too little space, or a file that
     *   fails the size or SHA-256 check (the partial file is deleted then; after a network failure it
     *   is kept for the next attempt).
     */
    suspend fun ensure(model: WhisperModelSpec, progress: ((Double) -> Unit)? = null): File =
        lockOf(model).withLock {
            withContext(io) { if (!isInstalled(model)) download(model, progress) }
            progress?.invoke(1.0)
            file(model)
        }

    /** Deletes [model] and any partial download of it. */
    suspend fun delete(model: WhisperModelSpec) = lockOf(model).withLock {
        withContext(io) {
            file(model).delete()
            partFile(model).delete()
        }
    }

    /** Deletes files in [directory] that are no model of the catalog (older revisions, strays). Never throws. */
    fun pruneUnknownFiles() {
        val known = catalog.flatMap { listOf(it.fileName, it.fileName + PART_SUFFIX) }.toSet()
        directory.listFiles()?.forEach { if (it.isFile && it.name !in known) it.delete() }
    }

    private fun partFile(model: WhisperModelSpec) = File(directory, model.fileName + PART_SUFFIX)

    private suspend fun download(model: WhisperModelSpec, progress: ((Double) -> Unit)?) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw WhisperModelException("could not create the model directory")
        }
        pruneUnknownFiles()
        val part = partFile(model)
        if (part.length() > model.sizeBytes) part.delete()
        if (part.length() < model.sizeBytes) {
            val needed = model.sizeBytes - part.length()
            if (usableSpace(directory) < needed + SPACE_MARGIN_BYTES) {
                throw WhisperModelException("not enough free space for the transcription model")
            }
            fetch(model, part, progress)
        }
        if (part.length() != model.sizeBytes) throw WhisperModelException("the model download ended early")
        val digest = sha256Hex(part)
        if (digest != model.sha256) {
            part.delete()
            throw WhisperModelException("the downloaded model failed its checksum")
        }
        if (!part.renameTo(file(model))) throw WhisperModelException("could not install the model")
    }

    /** One GET (ranged when a partial file exists) appended to [part]; cancelling the coroutine cancels the call. */
    private suspend fun fetch(model: WhisperModelSpec, part: File, progress: ((Double) -> Unit)?) = coroutineScope {
        val have = part.length()
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegment(model.fileName).build())
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        val call = client.newCall(request)
        val transfer = async(io) { transfer(call, model, part, have, progress) }
        try {
            transfer.await()
        } catch (cancelled: CancellationException) {
            call.cancel()
            throw cancelled
        }
    }

    private fun transfer(call: Call, model: WhisperModelSpec, part: File, have: Long, progress: ((Double) -> Unit)?) {
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (call.isCanceled()) throw CancellationException("the model download was cancelled")
            throw WhisperModelException("the model download failed", e)
        }
        response.use {
            val append = when (response.code) {
                206 -> {
                    // Only a range that starts where the partial file ends can be appended.
                    val start = response.header("Content-Range")?.let(::rangeStart)
                    if (start != have) {
                        part.delete()             // the next attempt starts over instead of failing again
                        throw WhisperModelException("the server answered a different range")
                    }
                    true
                }
                200 -> false                      // the server ignored the range: start over
                416 -> {
                    // Nothing left to send: either the partial file is already complete, or it is
                    // junk; the size and checksum check that follows decides.
                    if (have != model.sizeBytes) part.delete()
                    return
                }
                else -> throw WhisperModelException("the model download failed (HTTP ${response.code})")
            }
            val body = response.body
            var written = if (append) have else 0L
            var reported = -1L
            try {
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (written + read > model.sizeBytes) throw WhisperModelException("the model download is larger than expected")
                            out.write(buffer, 0, read)
                            written += read
                            val permille = written * 1000 / model.sizeBytes
                            if (permille != reported) {
                                reported = permille
                                progress?.invoke(written.toDouble() / model.sizeBytes)
                            }
                        }
                    }
                }
            } catch (e: WhisperModelException) {
                part.delete()
                throw e
            } catch (e: IOException) {
                // Network failure or a cancelled call: keep what arrived for the next attempt. A
                // cancelled call stays a cancellation, so the caller's coroutine unwinds quietly.
                if (call.isCanceled()) throw CancellationException("the model download was cancelled")
                throw WhisperModelException("the model download was interrupted", e)
            }
        }
    }

    companion object {
        /** `noBackupFilesDir/<DIRECTORY>` (00-plan §1.5). */
        const val DIRECTORY = "whisper"

        /** Pinned revision of the whisper.cpp model repository on Hugging Face (media §9.9). */
        const val PINNED_REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
        const val PINNED_BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/$PINNED_REVISION/"

        const val PART_SUFFIX = ".part"

        /** Headroom left on the disk after a download. */
        const val SPACE_MARGIN_BYTES = 50L * 1024 * 1024

        private const val BUFFER_BYTES = 64 * 1024

        /** Start of `Content-Range: bytes <start>-<end>/<total>`, or null. */
        internal fun rangeStart(header: String): Long? =
            Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""").find(header)?.groupValues?.get(1)?.toLongOrNull()

        /** Lower-case hex SHA-256 of [file]. */
        internal fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
