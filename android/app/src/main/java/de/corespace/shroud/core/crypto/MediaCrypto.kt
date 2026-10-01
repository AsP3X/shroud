package de.corespace.shroud.core.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.modes.GCMModeCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.InputStream
import java.io.OutputStream

/**
 * Media blob sealing (iOS `ios/shroud/Services/Crypto/MediaCrypto.swift:40-53`, web
 * `web/src/crypto/aes.ts:29-33`; crypto spec §13.1): every photo, video and voice note is sealed
 * under its own fresh 32-byte key with AES-256-GCM, `nonce (12) ‖ ciphertext ‖ tag (16)`. The key
 * travels **inside** the sealed message payload as `k = b64(key)` (`Services/API/MediaModels.swift:53-54`);
 * the server only ever stores the sealed blob.
 *
 * Two forms, byte-identical for the same key and nonce (standard 96-bit-nonce GCM, as CryptoKit and
 * WebCrypto):
 * - [sealFile]/[openFile] for small blobs in memory (JCA, as iOS loads `Data`);
 * - [sealStream]/[openStream] for anything up to [MAX_SEALED_BYTES], which no `ByteArray` holds:
 *   BouncyCastle's lightweight GCM emits output as it goes (the JCA/Conscrypt AEAD buffers the whole
 *   message). [openStream] writes plaintext **before** the tag is checked at the end — see there.
 *
 * Keys are drawn from an [Entropy] (key first, then nonce) so the vector of crypto spec §16.3 pins
 * every form. Accepted residuals, as in [Primitives]: BouncyCastle's `KeyParameter` and AES key
 * schedule keep copies of the key that cannot be wiped; they are garbage after the call. Never logs
 * keys or content.
 */
object MediaCrypto {
    /** iOS `MediaCrypto.MediaError` (`MediaCrypto.swift:33-37`), without the image-encoding case (media area). */
    sealed class MediaError(msg: String) : Exception(msg, null, false, false) {
        /** A media key that is not 32 bytes. */
        object InvalidKey : MediaError("invalid media key")

        /** A wrong key, a tampered or truncated blob. */
        object DecryptFailed : MediaError("media decrypt failed")
    }

    /** A sealed blob and its key. The caller owns both arrays; the key belongs in the sealed payload only. */
    class SealedMedia(val key: ByteArray, val sealed: ByteArray)

    const val KEY_BYTES = 32

    /** Nonce plus tag: a sealed blob is this much longer than its plaintext. */
    const val SEALED_OVERHEAD_BYTES = Primitives.GCM_NONCE_BYTES + Primitives.GCM_TAG_BYTES

    /** Server `MAX_MEDIA_BYTES` (`server/…/routes/media.rs:33`), iOS `VideoMedia.maxSealedBytes` (`VideoMedia.swift:121`). */
    const val MAX_SEALED_BYTES: Long = 2L * 1024 * 1024 * 1024

    /** iOS `VideoMedia.maxPlaintextBytes` (`VideoMedia.swift:119`): the sealed cap less 1 MiB of headroom. */
    const val MAX_PLAINTEXT_BYTES: Long = MAX_SEALED_BYTES - 1024 * 1024

    /**
     * Cap for the JPEG preview `th` inside a media payload (`MediaCrypto.swift:140-146`): message
     * envelopes over 64 KiB are refused and v2/v3 carry the payload two or three times.
     */
    const val MAX_ENVELOPE_PREVIEW_BYTES = 6 * 1024

    /** Streaming buffer size (crypto spec §13.1). */
    private const val STREAM_BUFFER_BYTES = 1024 * 1024

    /** Room for what GCM holds back: one block plus the tag. */
    private const val GCM_SLACK_BYTES = 32

    /**
     * Seals [plaintext] under a fresh key (`sealFile`, `MediaCrypto.swift:40-46`). Draws the
     * 32-byte key, then the 12-byte nonce.
     */
    fun sealFile(plaintext: ByteArray, entropy: Entropy = SystemEntropy): SealedMedia {
        val key = entropy.bytes(KEY_BYTES)
        val sealed = try {
            Primitives.aesGcmSeal(key, entropy.bytes(Primitives.GCM_NONCE_BYTES), plaintext)
        } catch (e: CryptoError) {
            key.fill(0)
            throw e
        }
        return SealedMedia(key, sealed)
    }

    /**
     * Opens a blob from [sealFile] or [sealStream] (`openFile`, `MediaCrypto.swift:48-53`). A key
     * that is not 32 bytes → [MediaError.InvalidKey]; anything that does not open (shorter than
     * 28 bytes, wrong key, tampered) → [MediaError.DecryptFailed].
     */
    fun openFile(sealed: ByteArray, key: ByteArray): ByteArray {
        if (key.size != KEY_BYTES) throw MediaError.InvalidKey
        return try {
            Primitives.aesGcmOpen(key, sealed)
        } catch (_: CryptoError) {
            throw MediaError.DecryptFailed
        }
    }

    /**
     * Seals [input] into [output] under a fresh key and returns the key: the nonce first, then the
     * ciphertext as it is produced, then the tag. Same draws and bytes as [sealFile]. Neither stream
     * is closed. An I/O error propagates (the partial output is garbage — delete it). The plaintext
     * buffers are zeroed before returning.
     */
    fun sealStream(input: InputStream, output: OutputStream, entropy: Entropy = SystemEntropy): ByteArray {
        val key = entropy.bytes(KEY_BYTES)
        try {
            val nonce = entropy.bytes(Primitives.GCM_NONCE_BYTES)
            val cipher = gcm(forEncryption = true, key, nonce)
            output.write(nonce)
            pump(cipher, input, output)
            return key
        } catch (e: Throwable) {
            key.fill(0)
            throw e
        }
    }

    /**
     * Opens a sealed stream into [output]. Plaintext is written **before** the tag is checked: a
     * wrong key, a tampered or truncated blob only throws [MediaError.DecryptFailed] at the end,
     * after unauthenticated bytes went out. Callers therefore write to a `shroud-` temp file or a
     * sealed cache entry, treat it as untrusted until this returns, and delete it on failure
     * (crypto spec §13.1, §15). A key that is not 32 bytes → [MediaError.InvalidKey]. I/O errors
     * propagate. Neither stream is closed.
     */
    fun openStream(input: InputStream, output: OutputStream, key: ByteArray) {
        if (key.size != KEY_BYTES) throw MediaError.InvalidKey
        val nonce = ByteArray(Primitives.GCM_NONCE_BYTES)
        if (readFully(input, nonce) != nonce.size) throw MediaError.DecryptFailed
        val cipher = gcm(forEncryption = false, key, nonce)
        try {
            pump(cipher, input, output)
        } catch (_: InvalidCipherTextException) {
            throw MediaError.DecryptFailed
        }
    }

    /** Reads until [buffer] is full or the stream ends; the count read. (`readNBytes` needs API 33.) */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var filled = 0
        while (filled < buffer.size) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }

    private fun gcm(forEncryption: Boolean, key: ByteArray, nonce: ByteArray): GCMModeCipher =
        GCMBlockCipher.newInstance(AESEngine.newInstance()).apply {
            init(forEncryption, AEADParameters(KeyParameter(key), Primitives.GCM_TAG_BYTES * 8, nonce))
        }

    /** Streams [input] through [cipher] into [output], then finishes (writes or checks the tag). */
    private fun pump(cipher: GCMModeCipher, input: InputStream, output: OutputStream) {
        val inBuffer = ByteArray(STREAM_BUFFER_BYTES)
        val outBuffer = ByteArray(STREAM_BUFFER_BYTES + GCM_SLACK_BYTES)
        try {
            while (true) {
                val read = input.read(inBuffer)
                if (read < 0) break
                if (read == 0) continue
                val produced = cipher.processBytes(inBuffer, 0, read, outBuffer, 0)
                if (produced > 0) output.write(outBuffer, 0, produced)
            }
            val produced = cipher.doFinal(outBuffer, 0)
            if (produced > 0) output.write(outBuffer, 0, produced)
        } finally {
            inBuffer.fill(0)
            outBuffer.fill(0)
        }
    }
}
