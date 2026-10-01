package de.corespace.shroud.core.media

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.Entropy
import de.corespace.shroud.core.crypto.MediaCrypto
import de.corespace.shroud.core.crypto.Primitives
import de.corespace.shroud.core.crypto.SystemEntropy
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.storage.SensitiveTempFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.bouncycastle.crypto.modes.gcm.Tables64kGCMMultiplier
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Upload and download of sealed media blobs — iOS `MediaService` (`ios/shroud/Services/API/MediaService.swift:7-84`)
 * plus the `MediaCrypto.sealFile`/`openFile` steps around it in `MessagingController`
 * (`MessagingController.swift:2834-2848`, `3058-3069`); media-voice-links §1.1, §1.2, §2,
 * api-realtime §2.8, §4.14, D8; crypto §13.1.
 *
 * **Upload** ([upload]): the blob is `nonce (12) ‖ AES-256-GCM(key, plaintext) ‖ tag (16)` under a
 * fresh 32-byte key (D1, byte-identical to iOS and the web), so its size is known before a single
 * byte is sealed: `plaintext + 28`. Over 2 GiB it fails **before any request** with iOS's
 * `VALIDATION_ERROR` text (`MediaService.swift:42-51`, N = max(1, sealed / 1 048 576)). Then, in
 * iOS's order, `POST media/uploads` with that exact size and `PUT media/{id}/content`; the PUT body
 * seals the plaintext as OkHttp writes it ([SealingBody]) — no sealed copy in memory or on disk for
 * large media. Key and nonce are fixed for the upload, so a body OkHttp writes a second time (a
 * retry on a fresh connection) is byte-identical. Plaintext up to [ONE_SHOT_BYTES] is sealed in one
 * JCA call (Conscrypt, hardware AES); larger sources stream through [StreamingGcm] — the platform's
 * AES/CTR plus a table GHASH, byte-identical to `MediaCrypto.sealStream` but not limited by
 * BouncyCastle's software AES (media D2 and risk R1; plan W1-CRYPTO acceptance note).
 * The upload row is created right before the PUT, as iOS does: the server deletes unlinked uploads
 * after 60 min (`media.rs:37-39`). Uploads are not cancellable once bytes move (iOS
 * `MessagingController.swift:2904-2911`), but cancelling the caller cancels the request.
 *
 * **Download** ([downloadInto]): `GET media/{id}/content`, opened with the payload's key and sealed
 * straight into the local cache under the target message id. The plaintext only ever exists in
 * memory, one buffer at a time, and goes into an uncommitted SHRM1 writer that is committed only
 * after the GCM tag checked; a wrong key, a tampered or truncated blob, a lock or a cancellation
 * leaves nothing behind (crypto §13.1). The sealed bytes pass through a `cacheDir/shroud-dl-*` file
 * first (ciphertext only — see contract change request CR-1 in the package report for streaming
 * straight from the response).
 *
 * Progress (0…1, [de.corespace.shroud.core.net.ProgressRequestBody] / `ProgressSource`): at most
 * every 100 ms and in steps of 0.005, never backwards, `1.0` exactly once at the end
 * (`TransferProgressObserver.swift:17-21, 56-78`). Callbacks run on I/O threads; the caller posts
 * them. Errors are the API client's (`ApiError`: server envelopes verbatim, transport texts, the
 * 401 / `DEVICE_REMOVED` session policy), [MediaCrypto.MediaError] for keys and blobs that do not
 * open, [CryptoError.Locked] while chats are locked. Never logs keys, ids or content.
 */
class MediaTransferService(
    private val api: ShroudApi,
    private val cache: LocalMediaStore,
    private val tempFiles: SensitiveTempFiles,
    private val entropy: Entropy = SystemEntropy,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MediaTransfers {

    /**
     * Seals [source] under a fresh key and uploads it; the result carries what the payload needs
     * (`media_object_id`, `k`, `s`). [PlainSource.InMemory]'s array must not change while the
     * upload runs.
     *
     * @throws ApiError.Server `VALIDATION_ERROR` 400 when the sealed blob would exceed 2 GiB (no request made).
     * @throws java.io.FileNotFoundException when a [PlainSource.LocalMedia] cannot be read (no file,
     *   chats locked) or a [PlainSource.TempFile] is missing (no request made).
     */
    override suspend fun upload(source: PlainSource, token: String, onProgress: ((Double) -> Unit)?): UploadedBlob {
        val plain = withContext(io) { Plain.of(source, cache) }
        val sealedSize = plain.length + MediaCrypto.SEALED_OVERHEAD_BYTES
        if (sealedSize > MediaCrypto.MAX_SEALED_BYTES) throw tooLarge(sealedSize)
        // Draw order as MediaCrypto.sealFile: the key, then the nonce (crypto §16.3 vectors).
        val key = entropy.bytes(MediaCrypto.KEY_BYTES)
        val nonce = entropy.bytes(Primitives.GCM_NONCE_BYTES)
        val body = SealingBody(plain, key, nonce, sealedSize)
        try {
            val upload = api.createMediaUpload(token, sealedSize)
            api.uploadMediaContent(token, upload.mediaObjectId, body, onProgress)
            return UploadedBlob(upload.mediaObjectId, B64.encode(key), plain.length, sealedSize)
        } finally {
            body.destroy()
            key.fill(0)
            nonce.fill(0)
        }
    }

    /**
     * Downloads [mediaObjectId], opens it with [keyBase64] (the payload's `k`, standard Base64)
     * and commits the plaintext to the local cache under [targetMessageId], replacing what was
     * there. Nothing is stored unless the whole blob authenticated.
     *
     * @throws MediaCrypto.MediaError.InvalidKey when [keyBase64] is not a strict Base64 32-byte key (no request made).
     * @throws MediaCrypto.MediaError.DecryptFailed when the blob does not open with it.
     * @throws CryptoError.Locked while chats are locked (no request made) or when they lock meanwhile.
     * @throws IOException when [targetMessageId] was removed from the cache meanwhile (a delete for
     *   everyone, `LocalMediaCache.remove`): nothing is stored.
     */
    override suspend fun downloadInto(
        mediaObjectId: UUID,
        keyBase64: String,
        token: String,
        targetMessageId: UUID,
        onProgress: ((Double) -> Unit)?,
    ) {
        val key = B64.decodeStrict(keyBase64)
        if (key == null || key.size != MediaCrypto.KEY_BYTES) {
            key?.fill(0)
            throw MediaCrypto.MediaError.InvalidKey
        }
        try {
            withContext(io) {
                cache.writer(targetMessageId).use { writer ->
                    val sealed = tempFiles.create(TEMP_STEM, TEMP_EXTENSION)
                    try {
                        api.downloadMediaContentTo(token, mediaObjectId, sealed, onProgress)
                        open(sealed, key, writer, currentCoroutineContext().job)
                        writer.commit()
                    } finally {
                        sealed.delete()
                    }
                }
            }
        } finally {
            key.fill(0)
        }
    }

    /** Opens the sealed file into [writer] (uncommitted); a bad tag → [MediaCrypto.MediaError.DecryptFailed]. */
    private fun open(sealed: File, key: ByteArray, writer: SealedMediaWriter, job: Job) {
        val length = sealed.length()
        if (length <= ONE_SHOT_BYTES + MediaCrypto.SEALED_OVERHEAD_BYTES) {
            val blob = sealed.readBytes()
            job.ensureActive()
            val plaintext = MediaCrypto.openFile(blob, key)
            try {
                writer.write(plaintext, 0, plaintext.size)
            } finally {
                plaintext.fill(0)
            }
        } else {
            CancellableInputStream(FileInputStream(sealed), job).use { input ->
                StreamingGcm.open(input, WriterOutputStream(writer), key)
            }
        }
    }

    /** The plaintext an upload reads, possibly more than once (OkHttp may write a body twice). */
    internal sealed class Plain(val length: Long) {
        abstract fun open(): InputStream

        /** All of it in memory: small sources only. */
        abstract fun readAll(): ByteArray

        class Memory(val data: ByteArray) : Plain(data.size.toLong()) {
            override fun open(): InputStream = ByteArrayInputStream(data)
            override fun readAll(): ByteArray = data
        }

        class Local(private val cache: LocalMediaStore, private val messageId: UUID, length: Long) : Plain(length) {
            override fun open(): InputStream = cache.openReader(messageId)?.inputStream() ?: throw IOException("local media is gone")
            override fun readAll(): ByteArray = open().use { it.readBytes() }
        }

        class TempFile(private val file: File) : Plain(file.length()) {
            override fun open(): InputStream = FileInputStream(file)
            override fun readAll(): ByteArray = file.readBytes()
        }

        companion object {
            fun of(source: PlainSource, cache: LocalMediaStore): Plain = when (source) {
                is PlainSource.InMemory -> Memory(source.data)
                is PlainSource.LocalMedia -> {
                    val length = cache.openReader(source.messageId)?.use { it.length }
                        ?: throw FileNotFoundException("local media is unavailable")
                    Local(cache, source.messageId, length)
                }
                is PlainSource.TempFile -> {
                    if (!source.file.isFile) throw FileNotFoundException("upload source is missing")
                    TempFile(source.file)
                }
            }
        }
    }

    /**
     * `nonce ‖ AES-256-GCM(key, plaintext) ‖ tag`, sealed while OkHttp writes it. Repeatable: every
     * [writeTo] emits the same bytes under the same key and nonce (media-voice-links §2.3).
     *
     * GCM must never seal two different plaintexts under one nonce, so a replay may only ever
     * repeat bytes: the one-shot path keeps the sealed bytes of its first write (ciphertext only)
     * and sends them again; the streaming path checks every 1 MiB chunk of plaintext against the
     * SHA-256 recorded when it was first sealed **before** sealing it again, and checks the length
     * before the tag is written — a source that changed fails the write instead.
     *
     * Holds its own copies of key and nonce; [destroy] zeroes them, after which a write fails rather
     * than sealing under a zeroed key (OkHttp may still touch a body after a cancelled call returned).
     */
    internal class SealingBody(
        private val plain: Plain,
        key: ByteArray,
        nonce: ByteArray,
        private val sealedSize: Long,
    ) : RequestBody() {
        private val key = key.copyOf() // guarded by this
        private val nonce = nonce.copyOf() // guarded by this
        private var destroyed = false // guarded by this
        private var oneShot: ByteArray? = null // guarded by this
        private val digests = ArrayList<ByteArray>() // guarded by itself: per-chunk SHA-256 of what was sealed

        override fun contentType(): MediaType = OCTET_STREAM

        override fun contentLength(): Long = sealedSize

        override fun isOneShot(): Boolean = false

        override fun writeTo(sink: BufferedSink) {
            if (plain.length <= ONE_SHOT_BYTES) {
                sink.write(sealedOnce())
                return
            }
            val (k, n) = snapshot()
            try {
                ReplayCheckedInputStream(plain.open(), plain.length, digests).use { input ->
                    // Same key and nonce on every write.
                    StreamingGcm.seal(input, sink.outputStream(), k, n)
                }
            } finally {
                k.fill(0)
                n.fill(0)
            }
        }

        /** Zeroes the key and nonce and drops the sealed bytes; later writes fail. */
        @Synchronized
        fun destroy() {
            destroyed = true
            key.fill(0)
            nonce.fill(0)
            oneShot = null
        }

        @Synchronized
        private fun snapshot(): Pair<ByteArray, ByteArray> {
            if (destroyed) throw IOException("upload finished")
            return key.copyOf() to nonce.copyOf()
        }

        @Synchronized
        private fun sealedOnce(): ByteArray {
            if (destroyed) throw IOException("upload finished")
            oneShot?.let { return it }
            val plaintext = plain.readAll()
            val sealed = try {
                if (plaintext.size.toLong() != plain.length) throw IOException("upload source changed size")
                Primitives.aesGcmSeal(key, nonce, plaintext)
            } catch (_: CryptoError) {
                throw IOException("media sealing failed")
            } finally {
                if (plain !is Plain.Memory) plaintext.fill(0)
            }
            oneShot = sealed
            return sealed
        }
    }

    /**
     * Hands plaintext to the cipher one verified 1 MiB chunk at a time: a chunk sealed by an
     * earlier write must hash the same now, and the stream must end exactly at [expectedLength] —
     * otherwise it throws before the chunk (or the end, which makes the cipher write its tag) is
     * released. Zeroes its buffer on close.
     */
    private class ReplayCheckedInputStream(
        private val input: InputStream,
        private val expectedLength: Long,
        private val digests: MutableList<ByteArray>,
    ) : InputStream() {
        private val chunk = ByteArray(REPLAY_CHUNK_BYTES)
        private var chunkLength = 0
        private var chunkPosition = 0
        private var index = 0
        private var total = 0L
        private var ended = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (chunkPosition == chunkLength && !fill()) return -1
            val count = minOf(len, chunkLength - chunkPosition)
            chunk.copyInto(b, off, chunkPosition, chunkPosition + count)
            chunkPosition += count
            return count
        }

        private fun fill(): Boolean {
            if (ended) return false
            var filled = 0
            while (filled < chunk.size) {
                val read = input.read(chunk, filled, chunk.size - filled)
                if (read < 0) {
                    ended = true
                    break
                }
                filled += read
            }
            total += filled
            if (total > expectedLength || (ended && total != expectedLength)) throw IOException("upload source changed size")
            if (filled == 0) return false
            val digest = MessageDigest.getInstance("SHA-256").apply { update(chunk, 0, filled) }.digest()
            synchronized(digests) {
                if (index < digests.size) {
                    if (!MessageDigest.isEqual(digests[index], digest)) throw IOException("upload source changed")
                } else {
                    digests += digest
                }
            }
            index++
            chunkLength = filled
            chunkPosition = 0
            return true
        }

        override fun close() {
            chunk.fill(0)
            input.close()
        }
    }

    /** Stops a long decrypt when the caller is cancelled (checked per read). */
    private class CancellableInputStream(input: InputStream, private val job: Job) : FilterInputStream(input) {
        override fun read(): Int {
            job.ensureActive()
            return super.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            job.ensureActive()
            return super.read(b, off, len)
        }
    }

    private class WriterOutputStream(private val writer: SealedMediaWriter) : OutputStream() {
        override fun write(b: Int) = writer.write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) = writer.write(b, off, len)
    }

    companion object {
        /** Below this, seal and open in one JCA call (media D2: Conscrypt one-shot ≤ 16 MiB, [StreamingGcm] above). */
        const val ONE_SHOT_BYTES = 16L * 1024 * 1024

        /** The streaming upload's replay check granularity: one SHA-256 per MiB of plaintext (64 KiB of digests for 2 GiB). */
        const val REPLAY_CHUNK_BYTES = 1024 * 1024

        /** `cacheDir/shroud-dl-*.sealed`: the downloaded ciphertext until it is opened into the cache. */
        const val TEMP_STEM = "dl"
        const val TEMP_EXTENSION = "sealed"

        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        /** `MediaService.uploadContent`'s refusal (`MediaService.swift:42-51`). */
        internal fun tooLarge(sealedSize: Long): ApiError.Server {
            val megabytes = maxOf(1L, sealedSize / 1_048_576L)
            return ApiError.Server(
                ErrorCodes.VALIDATION_ERROR,
                "This media is too large after encryption ($megabytes MB). Try a shorter video or lower photo quality.",
                400,
            )
        }
    }
}

/**
 * Streaming AES-256-GCM for media blobs larger than [MediaTransferService.ONE_SHOT_BYTES], built
 * from the platform's `AES/CTR/NoPadding` (Conscrypt on a phone: BoringSSL with the ARMv8 AES
 * instructions) and BouncyCastle's 64 KiB-table GHASH — media-voice-links risk R1 and the plan's
 * W1-CRYPTO acceptance note: BouncyCastle's all-Java GCM (`MediaCrypto.sealStream`/`openStream`)
 * measured 44–45 MB/s on the API 37 emulator, under the 50 MB/s target, so the hardware CTR path
 * is the one transfers use.
 *
 * It is GCM exactly as NIST SP 800-38D defines it for a 96-bit nonce and no AAD, so its output is
 * byte-identical to `MediaCrypto.sealFile`/`sealStream`, CryptoKit (`MediaCrypto.swift:40-53`) and
 * WebCrypto (`web/src/crypto/aes.ts:29-46`) — `StreamingGcmTest` pins it against all of them:
 * - `H = AES_K(0¹²⁸)`, `J0 = nonce ‖ 0x00000001`, keystream blocks `AES_K(nonce ‖ ctr)` from
 *   `ctr = 2`;
 * - `tag = AES_K(J0) ⊕ GHASH_H(C ‖ 0-pad ‖ [0]₆₄ ‖ [8·len(C)]₆₄)`;
 * - blob = `nonce ‖ C ‖ tag`.
 *
 * GCM increments only the low 32 bits of the counter block (inc32) while the JCA's CTR carries
 * into all 128; they agree as long as the low word does not wrap, i.e. for up to
 * [MAX_PLAINTEXT_BYTES] (≈ 64 GiB, far above the 2 GiB server cap), which [seal] and [open] enforce.
 *
 * [open] releases plaintext before the tag is checked at the end (like `MediaCrypto.openStream`):
 * callers write it into an uncommitted SHRM1 writer and commit only after it returned. Accepted
 * residuals, as in `MediaCrypto`: the JCA ciphers' key schedules and the GHASH tables (derived from
 * `H`, not the key) cannot be wiped; they are garbage after the call. Never logs keys or content.
 */
internal object StreamingGcm {
    private const val BLOCK_BYTES = 16
    private const val TAG_BYTES = Primitives.GCM_TAG_BYTES
    private const val NONCE_BYTES = Primitives.GCM_NONCE_BYTES

    /** Plaintext per pass through the ciphers. */
    private const val CHUNK_BYTES = 1024 * 1024

    /** Counter blocks 2 … 2³² − 1 carry the keystream: beyond this inc32 would wrap. */
    const val MAX_PLAINTEXT_BYTES: Long = (0xFFFF_FFFFL - 1) * BLOCK_BYTES

    /**
     * Writes `nonce ‖ AES-256-GCM(key, nonce, input) ‖ tag` to [output]. Neither stream is closed;
     * an I/O error propagates (the partial output is garbage). The buffers are zeroed before it
     * returns.
     */
    fun seal(input: InputStream, output: OutputStream, key: ByteArray, nonce: ByteArray) {
        val session = Session(key, nonce)
        val plain = ByteArray(CHUNK_BYTES)
        val sealed = ByteArray(CHUNK_BYTES + BLOCK_BYTES)
        try {
            output.write(nonce)
            while (true) {
                val read = input.read(plain)
                if (read < 0) break
                if (read == 0) continue
                val produced = session.crypt(plain, 0, read, sealed, encrypt = true)
                output.write(sealed, 0, produced)
            }
            val produced = session.finishCipher(sealed, encrypt = true)
            if (produced > 0) output.write(sealed, 0, produced)
            output.write(session.tag())
        } finally {
            plain.fill(0)
            sealed.fill(0)
            session.destroy()
        }
    }

    /**
     * Opens `nonce ‖ C ‖ tag` from [input] into [output]. Plaintext goes out **before** the tag is
     * checked at the end. A key that is not 32 bytes → [MediaCrypto.MediaError.InvalidKey]; a
     * stream shorter than 28 bytes, a wrong key, a tampered or truncated blob →
     * [MediaCrypto.MediaError.DecryptFailed] (after unauthenticated output). I/O errors propagate;
     * neither stream is closed.
     */
    fun open(input: InputStream, output: OutputStream, key: ByteArray) {
        if (key.size != MediaCrypto.KEY_BYTES) throw MediaCrypto.MediaError.InvalidKey
        val nonce = ByteArray(NONCE_BYTES)
        if (readFully(input, nonce) != NONCE_BYTES) throw MediaCrypto.MediaError.DecryptFailed
        val session = Session(key, nonce)
        // The last 16 bytes read so far might be the tag: they are held back until more follow.
        val sealed = ByteArray(CHUNK_BYTES + TAG_BYTES)
        val plain = ByteArray(CHUNK_BYTES + TAG_BYTES + BLOCK_BYTES)
        var held = 0
        try {
            while (true) {
                val read = input.read(sealed, held, sealed.size - held)
                if (read < 0) break
                held += read
                if (held <= TAG_BYTES) continue
                val ciphertext = held - TAG_BYTES
                val produced = session.crypt(sealed, 0, ciphertext, plain, encrypt = false)
                output.write(plain, 0, produced)
                sealed.copyInto(sealed, 0, ciphertext, held)
                held = TAG_BYTES
            }
            if (held != TAG_BYTES) throw MediaCrypto.MediaError.DecryptFailed
            val produced = session.finishCipher(plain, encrypt = false)
            if (produced > 0) output.write(plain, 0, produced)
            val expected = session.tag()
            val matches = MessageDigest.isEqual(expected, sealed.copyOf(TAG_BYTES))
            expected.fill(0)
            if (!matches) throw MediaCrypto.MediaError.DecryptFailed
        } finally {
            sealed.fill(0)
            plain.fill(0)
            session.destroy()
        }
    }

    /** Reads until [buffer] is full or the stream ends; the count read (`readNBytes` needs API 33). */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var filled = 0
        while (filled < buffer.size) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }

    /** One GCM message: the CTR cipher, the running GHASH over the ciphertext and the tag mask `AES_K(J0)`. */
    private class Session(key: ByteArray, nonce: ByteArray) {
        private val ctr: Cipher
        private val ghash: Ghash
        private val tagMask: ByteArray
        private var processed = 0L

        init {
            require(key.size == MediaCrypto.KEY_BYTES) { "media keys are 32 bytes" }
            require(nonce.size == NONCE_BYTES) { "GCM nonces are 12 bytes here" }
            val spec = SecretKeySpec(key, "AES")
            val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, spec) }
            val counter = ByteArray(BLOCK_BYTES)
            val h = ecb.doFinal(counter) // AES_K(0¹²⁸)
            nonce.copyInto(counter)
            counter[BLOCK_BYTES - 1] = 1
            tagMask = ecb.doFinal(counter) // AES_K(J0)
            counter[BLOCK_BYTES - 1] = 2 // inc32(J0): the first keystream block
            ctr = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, spec, IvParameterSpec(counter)) }
            ghash = Ghash(h)
            h.fill(0)
            counter.fill(0)
        }

        /** Encrypts or decrypts [length] bytes into [output]; GHASH always runs over the ciphertext side. */
        fun crypt(input: ByteArray, offset: Int, length: Int, output: ByteArray, encrypt: Boolean): Int {
            processed += length
            if (processed > MAX_PLAINTEXT_BYTES) throw IOException("media too large for one GCM message")
            if (!encrypt) ghash.update(input, offset, length)
            val produced = ctr.update(input, offset, length, output, 0)
            if (encrypt) ghash.update(output, 0, produced)
            return produced
        }

        /** Whatever the CTR cipher still holds (nothing for a stream mode, but the JCA allows it). */
        fun finishCipher(output: ByteArray, encrypt: Boolean): Int {
            val produced = ctr.doFinal(output, 0)
            if (encrypt && produced > 0) ghash.update(output, 0, produced)
            return produced
        }

        /** `AES_K(J0) ⊕ GHASH`; call once, after [finishCipher]. */
        fun tag(): ByteArray {
            val tag = ghash.finish()
            for (i in 0 until TAG_BYTES) tag[i] = (tag[i].toInt() xor tagMask[i].toInt()).toByte()
            return tag
        }

        fun destroy() {
            tagMask.fill(0)
            ghash.destroy()
        }
    }

    /** GHASH over a byte stream of any chunking (no AAD), then the lengths block. */
    private class Ghash(h: ByteArray) {
        private val multiplier = Tables64kGCMMultiplier().apply { init(h) }
        private val x = ByteArray(BLOCK_BYTES)
        private val partial = ByteArray(BLOCK_BYTES)
        private var partialLength = 0
        private var total = 0L

        fun update(data: ByteArray, offset: Int, length: Int) {
            var at = offset
            var left = length
            total += length
            if (partialLength > 0) {
                val take = minOf(left, BLOCK_BYTES - partialLength)
                data.copyInto(partial, partialLength, at, at + take)
                partialLength += take
                at += take
                left -= take
                if (partialLength < BLOCK_BYTES) return
                block(partial, 0)
                partialLength = 0
            }
            while (left >= BLOCK_BYTES) {
                block(data, at)
                at += BLOCK_BYTES
                left -= BLOCK_BYTES
            }
            if (left > 0) {
                data.copyInto(partial, 0, at, at + left)
                partialLength = left
            }
        }

        /** Pads the last block with zeros, folds in `[0]₆₄ ‖ [8·len(C)]₆₄` and returns the hash. */
        fun finish(): ByteArray {
            if (partialLength > 0) {
                partial.fill(0, partialLength)
                block(partial, 0)
                partialLength = 0
            }
            val lengths = ByteArray(BLOCK_BYTES)
            val bits = total * 8
            for (i in 0 until 8) lengths[BLOCK_BYTES - 1 - i] = (bits ushr (8 * i)).toByte()
            block(lengths, 0)
            return x.copyOf()
        }

        fun destroy() {
            x.fill(0)
            partial.fill(0)
        }

        private fun block(source: ByteArray, offset: Int) {
            for (i in 0 until BLOCK_BYTES) x[i] = (x[i].toInt() xor source[offset + i].toInt()).toByte()
            multiplier.multiplyH(x)
        }
    }
}
