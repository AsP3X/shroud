package de.corespace.shroud.core.media

import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.Entropy
import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.crypto.Primitives
import de.corespace.shroud.core.crypto.SystemEntropy
import de.corespace.shroud.core.crypto.utf8
import de.corespace.shroud.core.keys.LocalNames
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The sealed local media cache: every photo, video and voice note this device holds, keyed by
 * message id — iOS `LocalMediaCache` (`ios/shroud/Services/Messaging/LocalMediaCache.swift:11-97`;
 * media-voice-links §7.1, plan §1.5, C7, C10).
 *
 * iOS seals each file whole (`LocalHistoryCrypto.seal(data, historyKey, .mediaFile)`, `"SHRD1"`,
 * `:52-64`) and decrypts videos into `tmp/` to play them. Android uses the local-only **SHRM1**
 * format instead (media D3): fixed 64 KiB segments, each its own AES-256-GCM message, so players and
 * `MediaMetadataRetriever` read any byte range straight from the sealed file ([openReader],
 * `SealedMediaDataSource`) and no decrypted video ever lands on disk (media D4):
 *
 * ```
 * header  = "SHRM1" (5) ‖ salt (16, random) ‖ noncePrefix (7, random) ‖ segmentSize u32 BE (65536)   // 32 bytes
 * fileKey = HKDF-SHA256(ikm = LocalHistoryCrypto.subkey(historyKey, MediaFile),   // iOS's context
 *                       salt = salt, info = "shroud-local-media-stream-v1", L = 32)
 * nonce_i = noncePrefix ‖ u32 BE(i) ‖ (last ? 0x01 : 0x00)
 * ct_i    = AES-256-GCM(fileKey, nonce_i, plaintext[i·64K ..< min((i+1)·64K, n)], aad = header) ‖ tag
 * file    = header ‖ ct_0 ‖ … ‖ ct_last     // every ct_i is 64 KiB + 16 except the last (16 … 64 KiB + 16)
 * ```
 *
 * - **Integrity.** The header is every segment's AAD; the index and the last-segment flag sit in
 *   the nonce, so reordering, truncating, appending or splicing segments of another file fails.
 *   [openReader] authenticates the last segment before it reports a [SealedMediaReader.length], so
 *   a length is never trusted before its tag. Empty media is one empty last segment.
 * - **Names.** Files are `noBackupFilesDir/shroud/media/<LocalNames.name("media", id)>.sealed`
 *   (keyed names, plan §1.5 and C10; iOS uses the plain id). The id is not bound into the key, so
 *   [rename] can move a file when the server re-keys a sent message (iOS saves again under the
 *   server id and removes the optimistic one, `MessagingController.swift:2851-2855`).
 * - **Chat lock.** Every read and write needs the history key (iOS passes it to every call). While
 *   chats are locked [openReader]/[readAll] find nothing, [has] is false, [writer] throws
 *   [CryptoError.Locked] and [save] does nothing (iOS `save` is a silent no-op without a sealed
 *   blob, `:53-57`). Locking zeroes the file keys of every open reader and writer: readers fail
 *   their next read, unfinished writers delete their partial file. [rename] and [remove] calls
 *   made while locked are remembered in memory and applied at the next unlock.
 * - **Writes are atomic.** A [writer] streams into `<name>.<random>.pending` and [SealedMediaWriter.commit]
 *   renames it into place after an `fsync`; [SealedMediaWriter.abort] (or closing without a
 *   commit) deletes it. A pending file left by a killed process is deleted before the first writer
 *   of the next process starts.
 * - **Wipe.** While [StorageSeal.isSealed] nothing is committed (plan §1.4); [clearAll] closes
 *   every reader and writer and deletes the directory, which the next write re-creates (iOS
 *   `clearAll`, `:73-77`, and `save`, `:59-60`).
 *
 * Segments go through the JCA's AES-GCM (Conscrypt on a device, hardware AES), one-shot per 64 KiB.
 * Blocking API apart from the `suspend` members (which run on [io]): call it off the main thread.
 * Thread-safe; one reader or writer must not be shared between threads without its own ordering
 * (each method is synchronized). Never logs ids, names, keys or content.
 */
class LocalMediaCache(
    /** `noBackupFilesDir/shroud/media`. */
    val directory: File,
    private val state: SealedLocalState,
    private val storageSeal: StorageSeal,
    private val entropy: Entropy = SystemEntropy,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LocalMediaStore {
    private val handles: MutableSet<Handle> = ConcurrentHashMap.newKeySet()
    private val deferred = ArrayList<DeferredChange>() // guarded by itself
    private val pendingSwept = AtomicBoolean(false)

    /** Counts locks, so a handle registered while a lock ran can tell it missed the invalidation. */
    private val lockGeneration = AtomicLong()

    private val lockListener = object : SealedLocalState.Listener {
        override fun onUnlock(historyKey: ByteArray) = applyDeferred()

        override fun onLock() {
            lockGeneration.incrementAndGet()
            invalidateHandles()
        }
    }
    private val registration: AutoCloseable = state.addListener(lockListener)

    // ---- Reading ----

    /**
     * The whole plaintext of [messageId] (photos, voice notes, link images): null when there is no
     * file, chats are locked, the file does not open, or it is larger than [READ_ALL_LIMIT_BYTES]
     * (read those through [openReader]). iOS `data(for:historyKey:)` (`LocalMediaCache.swift:24-50`).
     */
    override suspend fun readAll(messageId: UUID): ByteArray? = withContext(io) { readAllBlocking(messageId) }

    /** [readAll] on the calling thread. */
    fun readAllBlocking(messageId: UUID): ByteArray? {
        val reader = openReader(messageId) ?: return null
        return reader.use { readFully(it) }
    }

    /** True when a sealed file exists for [messageId]; false while chats are locked. A stat, no decryption. */
    override fun has(messageId: UUID): Boolean = sealedFile(messageId)?.isFile == true

    /**
     * Random access into [messageId]'s plaintext; null when there is none, chats are locked, or
     * the file does not open (bad header, a last segment that does not authenticate). The caller
     * closes it.
     */
    override fun openReader(messageId: UUID): SealedMediaReader? {
        val file = sealedFile(messageId) ?: return null
        return try {
            openFile(file)
        } catch (_: IOException) {
            null
        } catch (_: CryptoError) {
            null
        }
    }

    /** A sequential stream over [messageId]'s plaintext (decoders, uploads); null like [openReader]. */
    fun openStream(messageId: UUID): InputStream? = openReader(messageId)?.inputStream()

    // ---- Writing ----

    /**
     * Seals [data] under [messageId], replacing any earlier file (iOS `save`,
     * `LocalMediaCache.swift:52-64`). Silent, like iOS, when chats are locked, the store is sealed
     * for a wipe, or the disk refuses.
     */
    override suspend fun save(messageId: UUID, data: ByteArray) = withContext(io) { saveBlocking(messageId, data) }

    /** [save] on the calling thread. */
    fun saveBlocking(messageId: UUID, data: ByteArray) {
        try {
            writer(messageId).use { writer ->
                writer.write(data, 0, data.size)
                writer.commit()
            }
        } catch (_: CryptoError) {
            // Locked: nothing is written without the history key.
        } catch (_: IOException) {
            // Disk full or the directory vanished under a wipe: as iOS's `try? write`.
        }
    }

    /**
     * A streaming writer for [messageId] (downloads, recordings, encoder output). Nothing is
     * visible until [SealedMediaWriter.commit], which replaces any earlier file. While the store is
     * sealed for a wipe the writer accepts and drops everything.
     *
     * @throws CryptoError.Locked while chats are locked.
     * @throws IOException when the pending file cannot be created.
     */
    override fun writer(messageId: UUID): SealedMediaWriter {
        if (storageSeal.isSealed) return DroppingWriter()
        if (!state.isUnlocked) throw CryptoError.Locked
        val salt = entropy.bytes(Shrm1.SALT_BYTES)
        val prefix = entropy.bytes(Shrm1.NONCE_PREFIX_BYTES)
        val header = Shrm1.header(salt, prefix)
        val generation = lockGeneration.get()
        // Name and key under one read lock, so both come from the same history key.
        val (name, fileKey) = state.withSubkey(LocalHistoryCrypto.Context.MediaFile) { subkey ->
            val names = state.names() ?: return@withSubkey null
            val name = nameOrNull(names, messageId) ?: return@withSubkey null
            name to Shrm1.fileKey(subkey, salt)
        } ?: throw CryptoError.Locked
        val writer = try {
            ensureDirectory()
            sweepPendingOnce()
            val pending = File(directory, name + "." + UUID.randomUUID().toString().replace("-", "") + PENDING_SUFFIX)
            Shrm1Writer(pending, File(directory, name + SEALED_SUFFIX), header, fileKey, prefix, storageSeal, ::release)
        } catch (e: IOException) {
            fileKey.fill(0)
            throw e
        }
        register(writer, generation)
        return writer
    }

    /**
     * Moves [from]'s file to [to] (the server re-keyed a sent message), replacing what [to] had.
     * Nothing when [from] has no file. Remembered until the next unlock while chats are locked;
     * dropped while the store is sealed for a wipe.
     */
    override fun rename(from: UUID, to: UUID) {
        if (from == to || storageSeal.isSealed) return
        val names = state.names()
        if (names == null || !renameNow(names, from, to)) defer(DeferredChange.Rename(from, to))
    }

    /**
     * Deletes the files of [messageIds] (deletes for everyone, retention pruning, purges; iOS
     * `remove(messageIDs:)`, `:66-71`) and withdraws their unfinished writers, whose commit then
     * fails, so a download racing a delete cannot bring the media back. Remembered until the next
     * unlock while chats are locked (when every writer is already gone).
     */
    override fun remove(messageIds: Collection<UUID>) {
        if (messageIds.isEmpty()) return
        val names = state.names()
        if (names == null || !removeNow(names, messageIds)) defer(DeferredChange.Remove(messageIds.toList()))
    }

    /**
     * Deletes every media file (Log Out / removal wipe, iOS `clearAll`, `:73-77`): open readers and
     * writers are closed first, remembered renames and removals are dropped. Works while locked.
     */
    override fun clearAll() {
        synchronized(deferred) { deferred.clear() }
        invalidateHandles()
        directory.deleteRecursively()
    }

    /** Files and bytes under [directory] (pending files included), for the wipe overlay. Works while locked. */
    override fun inventory(): Pair<Int, Long> {
        if (!directory.isDirectory) return 0 to 0L
        var count = 0
        var bytes = 0L
        directory.walkTopDown().filter { it.isFile }.forEach {
            count++
            bytes += it.length()
        }
        return count to bytes
    }

    /** Stops listening to the chat lock (tests; the app's cache lives as long as the process). */
    fun close() {
        registration.close()
    }

    // ---- Internals ----

    /** `<name>.sealed` of [messageId], or null while chats are locked. */
    private fun sealedFile(messageId: UUID): File? = mediaName(messageId)?.let { File(directory, it + SEALED_SUFFIX) }

    private fun mediaName(messageId: UUID): String? = state.names()?.let { nameOrNull(it, messageId) }

    private fun nameOrNull(names: LocalNames, messageId: UUID): String? = try {
        names.name(LocalNames.Kind.MEDIA, messageId)
    } catch (_: CryptoError.Locked) {
        null // locked between handing out the names and using them
    }

    /** Opens and authenticates [file]; null when it is not a SHRM1 file this key opens (or chats locked). */
    private fun openFile(file: File): SealedMediaReader? {
        if (!file.isFile) return null
        val raf = RandomAccessFile(file, "r")
        var reader: Shrm1Reader? = null
        var handedOut = false
        try {
            val layout = Shrm1.layout(raf.length()) ?: return null
            val header = ByteArray(Shrm1.HEADER_BYTES)
            raf.seek(0)
            raf.readFully(header)
            val parsed = Shrm1.parseHeader(header) ?: return null
            val generation = lockGeneration.get()
            val fileKey = state.withSubkey(LocalHistoryCrypto.Context.MediaFile) { Shrm1.fileKey(it, parsed.salt) } ?: return null
            val opened = Shrm1Reader(raf, header, parsed.noncePrefix, fileKey, layout, ::release)
            reader = opened
            if (!opened.authenticateLast()) return null
            register(opened, generation)
            handedOut = true
            return opened
        } finally {
            // Anything but a returned reader closes the file (the reader also zeroes its key).
            if (!handedOut) reader?.close() ?: raf.close()
        }
    }

    /**
     * Tracks [handle] for the lock. A lock whose listener ran between deriving the handle's key
     * (at [generation]) and this registration missed it, so after registering the state and the
     * generation are checked again: the key is zeroed if any lock happened in between, even one
     * already followed by an unlock (no key may outlive a lock, plan §1.4).
     */
    private fun register(handle: Handle, generation: Long) {
        handles += handle
        if (!state.isUnlocked || lockGeneration.get() != generation) {
            handle.invalidate()
            throw CryptoError.Locked
        }
    }

    private fun release(handle: Handle) {
        handles -= handle
    }

    private fun invalidateHandles() {
        for (handle in handles.toList()) handle.invalidate()
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("could not create the media directory")
        }
    }

    /** Pending files of an earlier process (killed mid-write) are useless: delete them once. */
    private fun sweepPendingOnce() {
        if (!pendingSwept.compareAndSet(false, true)) return
        val live = handles.mapNotNull { (it as? Shrm1Writer)?.pendingFile }.toSet()
        directory.listFiles()?.forEach { file ->
            if (file.name.endsWith(PENDING_SUFFIX) && file !in live) file.delete()
        }
    }

    private fun renameNow(names: LocalNames, from: UUID, to: UUID): Boolean {
        val source = nameOrNull(names, from) ?: return false
        val target = nameOrNull(names, to) ?: return false
        val sourceFile = File(directory, source + SEALED_SUFFIX)
        if (!sourceFile.isFile) return true
        sourceFile.renameTo(File(directory, target + SEALED_SUFFIX))
        return true
    }

    /**
     * Withdraws the unfinished writers of [messageIds] first, then deletes their files: a download
     * that finishes after a delete for everyone must not bring the media back (web
     * `preview.selftest`: a deleted message does not come back through a cache; the web checks
     * `isWithdrawn` before `saveMediaBlob`, `messaging.ts:583-585`). A writer withdrawn this way
     * fails its commit; one opened after the removal writes normally (a message pruned locally and
     * fetched again later).
     */
    private fun removeNow(names: LocalNames, messageIds: Collection<UUID>): Boolean {
        val targets = HashSet<File>()
        for (id in messageIds) {
            val name = nameOrNull(names, id) ?: return false
            targets += File(directory, name + SEALED_SUFFIX)
        }
        for (handle in handles.toList()) {
            if (handle is Shrm1Writer && handle.target in targets) handle.withdraw()
        }
        for (target in targets) target.delete()
        return true
    }

    private fun defer(change: DeferredChange) {
        synchronized(deferred) { deferred += change }
    }

    /** Applies what was asked while chats were locked, in order (called at unlock). */
    private fun applyDeferred() {
        val changes = synchronized(deferred) { deferred.toList().also { deferred.clear() } }
        if (changes.isEmpty()) return
        for (change in changes) {
            when (change) {
                is DeferredChange.Rename -> rename(change.from, change.to)
                is DeferredChange.Remove -> remove(change.ids)
            }
        }
    }

    private sealed interface DeferredChange {
        class Rename(val from: UUID, val to: UUID) : DeferredChange
        class Remove(val ids: List<UUID>) : DeferredChange
    }

    companion object {
        const val SEALED_SUFFIX = ".sealed"
        const val PENDING_SUFFIX = ".pending"

        /**
         * [readAll]'s ceiling: photos and voice notes fit easily; a video of hundreds of MB would
         * not fit the heap and is read through [openReader] instead.
         */
        const val READ_ALL_LIMIT_BYTES = 256L * 1024 * 1024

        /** Reads all of [reader] into memory; null over [READ_ALL_LIMIT_BYTES] or when a segment does not open. */
        internal fun readFully(reader: SealedMediaReader): ByteArray? {
            if (reader.length > READ_ALL_LIMIT_BYTES) return null
            val out = ByteArray(reader.length.toInt())
            var filled = 0
            try {
                while (filled < out.size) {
                    val read = reader.read(filled.toLong(), out, filled, out.size - filled)
                    if (read <= 0) break
                    filled += read
                }
            } catch (_: IOException) {
                out.fill(0)
                return null
            }
            if (filled != out.size) {
                out.fill(0)
                return null
            }
            return out
        }
    }
}

/** A sequential [InputStream] over this reader from position 0; closing it closes the reader. */
fun SealedMediaReader.inputStream(): InputStream = object : InputStream() {
    private var position = 0L
    private val single = ByteArray(1)

    override fun read(): Int = if (read(single, 0, 1) <= 0) -1 else single[0].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val count = this@inputStream.read(position, b, off, len)
        if (count > 0) position += count
        return count
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val skipped = minOf(n, (this@inputStream.length - position).coerceAtLeast(0))
        position += skipped
        return skipped
    }

    override fun available(): Int = (this@inputStream.length - position).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    override fun close() = this@inputStream.close()
}

/** What the lock invalidates: an open reader or an unfinished writer. */
internal interface Handle {
    /** Zeroes the key; a writer also deletes its pending file. Idempotent. */
    fun invalidate()
}

/**
 * The SHRM1 format constants and pure helpers (media-voice-links §7.1). Local-only: no other
 * client reads these files, so the format can change behind a new magic.
 */
internal object Shrm1 {
    /** `"SHRM1"`. */
    val MAGIC = byteArrayOf(0x53, 0x48, 0x52, 0x4D, 0x31)
    const val SALT_BYTES = 16
    const val NONCE_PREFIX_BYTES = 7
    const val HEADER_BYTES = 5 + SALT_BYTES + NONCE_PREFIX_BYTES + 4
    const val SEGMENT_BYTES = 65_536
    const val TAG_BYTES = Primitives.GCM_TAG_BYTES
    const val SEALED_SEGMENT_BYTES = SEGMENT_BYTES + TAG_BYTES
    const val FILE_KEY_INFO = "shroud-local-media-stream-v1"
    const val MAX_SEGMENTS = 0xFFFF_FFFFL + 1

    class Header(val salt: ByteArray, val noncePrefix: ByteArray)

    /** Where the segments are, from the file size alone (authenticated later, segment by segment). */
    class Layout(val segments: Long, val plaintextLength: Long, val lastSealedLength: Int) {
        fun sealedLength(index: Long): Int = if (index == segments - 1) lastSealedLength else SEALED_SEGMENT_BYTES
        fun sealedOffset(index: Long): Long = HEADER_BYTES + index * SEALED_SEGMENT_BYTES
    }

    fun header(salt: ByteArray, noncePrefix: ByteArray): ByteArray {
        require(salt.size == SALT_BYTES && noncePrefix.size == NONCE_PREFIX_BYTES)
        val header = ByteArray(HEADER_BYTES)
        MAGIC.copyInto(header, 0)
        salt.copyInto(header, MAGIC.size)
        noncePrefix.copyInto(header, MAGIC.size + SALT_BYTES)
        putU32(header, HEADER_BYTES - 4, SEGMENT_BYTES.toLong())
        return header
    }

    /** Magic and the one supported segment size, else null. */
    fun parseHeader(header: ByteArray): Header? {
        if (header.size != HEADER_BYTES) return null
        for (i in MAGIC.indices) if (header[i] != MAGIC[i]) return null
        if (u32(header, HEADER_BYTES - 4) != SEGMENT_BYTES.toLong()) return null
        val salt = header.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val prefix = header.copyOfRange(MAGIC.size + SALT_BYTES, MAGIC.size + SALT_BYTES + NONCE_PREFIX_BYTES)
        return Header(salt, prefix)
    }

    /** Null when [fileSize] cannot be a SHRM1 file (no room for one tag, a last segment shorter than a tag). */
    fun layout(fileSize: Long): Layout? {
        val body = fileSize - HEADER_BYTES
        if (body < TAG_BYTES) return null
        val segments = (body + SEALED_SEGMENT_BYTES - 1) / SEALED_SEGMENT_BYTES
        if (segments > MAX_SEGMENTS) return null
        val last = body - (segments - 1) * SEALED_SEGMENT_BYTES
        if (last < TAG_BYTES) return null
        return Layout(segments, body - segments * TAG_BYTES, last.toInt())
    }

    /** `HKDF-SHA256(mediaSubkey, salt, "shroud-local-media-stream-v1", 32)`; the caller zeroes it. */
    fun fileKey(mediaSubkey: ByteArray, salt: ByteArray): ByteArray = Primitives.hkdf(mediaSubkey, salt, utf8(FILE_KEY_INFO), 32)

    /** `noncePrefix ‖ u32 BE(index) ‖ last` (12 bytes). */
    fun nonce(noncePrefix: ByteArray, index: Long, last: Boolean): ByteArray {
        require(index in 0 until MAX_SEGMENTS)
        val nonce = ByteArray(Primitives.GCM_NONCE_BYTES)
        noncePrefix.copyInto(nonce, 0)
        putU32(nonce, NONCE_PREFIX_BYTES, index)
        nonce[NONCE_PREFIX_BYTES + 4] = if (last) 1 else 0
        return nonce
    }

    private fun putU32(target: ByteArray, at: Int, value: Long) {
        target[at] = (value ushr 24).toByte()
        target[at + 1] = (value ushr 16).toByte()
        target[at + 2] = (value ushr 8).toByte()
        target[at + 3] = value.toByte()
    }

    private fun u32(source: ByteArray, at: Int): Long =
        ((source[at].toLong() and 0xFF) shl 24) or ((source[at + 1].toLong() and 0xFF) shl 16) or
            ((source[at + 2].toLong() and 0xFF) shl 8) or (source[at + 3].toLong() and 0xFF)

    /**
     * One segment through AES-256-GCM with [header] as AAD; returns the output length. A segment
     * that does not authenticate → [IOException] (no plaintext is released: the JCA's GCM decrypt
     * only returns output after the tag checked). The key spec is made per call so no long-lived
     * copy of [fileKey] exists outside the caller's array (a `SecretKeySpec` cannot be zeroed).
     */
    fun crypt(
        cipher: Cipher,
        encrypt: Boolean,
        fileKey: ByteArray,
        noncePrefix: ByteArray,
        header: ByteArray,
        index: Long,
        last: Boolean,
        input: ByteArray,
        inputLength: Int,
        output: ByteArray,
    ): Int = try {
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(fileKey, "AES"),
            GCMParameterSpec(TAG_BYTES * 8, nonce(noncePrefix, index, last)),
        )
        cipher.updateAAD(header)
        cipher.doFinal(input, 0, inputLength, output, 0)
    } catch (e: GeneralSecurityException) {
        if (!encrypt) output.fill(0) // nothing unauthenticated survives a failed open
        throw IOException(if (encrypt) "SHRM1 segment could not be sealed" else "SHRM1 segment does not open", e)
    } catch (e: IllegalStateException) {
        if (!encrypt) output.fill(0)
        throw IOException("SHRM1 cipher failed", e)
    }

    /** Output room for one segment either way, whatever a provider's `getOutputSize` asks. */
    const val BUFFER_BYTES = SEALED_SEGMENT_BYTES + TAG_BYTES

    fun newCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")
}

/** Random access into one SHRM1 file; caches the last decrypted segment. */
private class Shrm1Reader(
    private val file: RandomAccessFile,
    private val header: ByteArray,
    private val noncePrefix: ByteArray,
    fileKey: ByteArray,
    private val layout: Shrm1.Layout,
    private val onClosed: (Handle) -> Unit,
) : SealedMediaReader, Handle {
    private var key: ByteArray? = fileKey // zeroed by close/invalidate
    private var closed = false
    private val cipher = Shrm1.newCipher()
    private val sealed = ByteArray(Shrm1.SEALED_SEGMENT_BYTES)
    private val plain = ByteArray(Shrm1.BUFFER_BYTES)
    private var cachedIndex = -1L
    private var cachedLength = 0

    override val length: Long get() = layout.plaintextLength

    /** Decrypts the last segment: the length is only trusted once its tag checked. */
    @Synchronized
    fun authenticateLast(): Boolean = try {
        load(layout.segments - 1)
        true
    } catch (_: IOException) {
        false
    }

    @Synchronized
    override fun read(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (offset < 0 || size < 0 || offset > buffer.size - size) throw IndexOutOfBoundsException()
        require(position >= 0) { "negative position" }
        if (closed) throw IOException("reader closed")
        if (key == null) throw IOException("media cache locked", CryptoError.Locked)
        if (size == 0) return 0
        if (position >= layout.plaintextLength) return -1
        var done = 0
        var at = position
        while (done < size && at < layout.plaintextLength) {
            val index = at / Shrm1.SEGMENT_BYTES
            load(index)
            val inSegment = (at - index * Shrm1.SEGMENT_BYTES).toInt()
            val count = minOf(size - done, cachedLength - inSegment)
            if (count <= 0) break
            plain.copyInto(buffer, offset + done, inSegment, inSegment + count)
            done += count
            at += count
        }
        return done
    }

    private fun load(index: Long) {
        if (index == cachedIndex) return
        val fileKey = key ?: throw IOException("media cache locked", CryptoError.Locked)
        val sealedLength = layout.sealedLength(index)
        file.seek(layout.sealedOffset(index))
        file.readFully(sealed, 0, sealedLength)
        cachedIndex = -1
        cachedLength = Shrm1.crypt(cipher, false, fileKey, noncePrefix, header, index, index == layout.segments - 1, sealed, sealedLength, plain)
        cachedIndex = index
    }

    @Synchronized
    override fun invalidate() {
        key?.fill(0)
        key = null
        plain.fill(0)
        cachedIndex = -1
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        invalidate()
        try {
            file.close()
        } finally {
            onClosed(this)
        }
    }
}

/** Streams plaintext into a pending SHRM1 file; [commit] seals the last segment and renames it into place. */
private class Shrm1Writer(
    val pendingFile: File,
    val target: File,
    private val header: ByteArray,
    fileKey: ByteArray,
    private val noncePrefix: ByteArray,
    private val storageSeal: StorageSeal,
    private val onFinished: (Handle) -> Unit,
) : SealedMediaWriter, Handle {
    private enum class Phase { Open, Committed, Aborted, Locked, Withdrawn }

    private var key: ByteArray? = fileKey
    private var phase = Phase.Open
    private val out: FileOutputStream = FileOutputStream(pendingFile)
    private val cipher = Shrm1.newCipher()
    private val plain = ByteArray(Shrm1.SEGMENT_BYTES)
    private val sealed = ByteArray(Shrm1.BUFFER_BYTES)
    private var filled = 0
    private var index = 0L

    init {
        try {
            out.write(header)
        } catch (e: IOException) {
            discard()
            throw e
        }
    }

    @Synchronized
    override fun write(buffer: ByteArray, offset: Int, size: Int) {
        if (offset < 0 || size < 0 || offset > buffer.size - size) throw IndexOutOfBoundsException()
        ensureOpen()
        var at = offset
        var remaining = size
        try {
            while (remaining > 0) {
                // A full segment is only sealed once more bytes follow: the last one carries the flag.
                if (filled == Shrm1.SEGMENT_BYTES) sealSegment(last = false)
                val count = minOf(remaining, Shrm1.SEGMENT_BYTES - filled)
                buffer.copyInto(plain, filled, at, at + count)
                filled += count
                at += count
                remaining -= count
            }
        } catch (e: IOException) {
            // A disk error leaves a broken file: drop it, the writer is finished.
            discard()
            throw e
        }
    }

    /**
     * Seals the last segment, syncs and renames the file into place (replacing any earlier one).
     * While the store is sealed for a wipe the file is deleted instead and nothing is committed.
     *
     * @throws CryptoError.Locked when chats locked meanwhile (the partial file is already gone).
     */
    @Synchronized
    override fun commit() {
        ensureOpen()
        try {
            sealSegment(last = true)
            out.flush()
            out.fd.sync()
            out.close()
            if (storageSeal.isSealed) {
                pendingFile.delete()
            } else if (!pendingFile.renameTo(target)) {
                throw IOException("could not commit the media file")
            }
            finish(Phase.Committed)
        } catch (e: IOException) {
            discard()
            throw e
        }
    }

    @Synchronized
    override fun abort() {
        if (phase != Phase.Open) return
        discard()
    }

    @Synchronized
    override fun close() {
        if (phase == Phase.Open) discard()
    }

    @Synchronized
    override fun invalidate() {
        if (phase != Phase.Open) return
        discard(Phase.Locked)
    }

    /** The id's media was removed while this writer ran: drop the partial file; [commit] then fails. */
    @Synchronized
    fun withdraw() {
        if (phase != Phase.Open) return
        discard(Phase.Withdrawn)
    }

    private fun ensureOpen() {
        when (phase) {
            Phase.Open -> Unit
            Phase.Locked -> throw CryptoError.Locked
            Phase.Withdrawn -> throw IOException("media removed while it was written")
            Phase.Committed, Phase.Aborted -> throw IOException("writer finished")
        }
    }

    private fun sealSegment(last: Boolean) {
        val fileKey = key ?: throw CryptoError.Locked
        if (index >= Shrm1.MAX_SEGMENTS) throw IOException("media too large for SHRM1")
        val count = Shrm1.crypt(cipher, true, fileKey, noncePrefix, header, index, last, plain, filled, sealed)
        out.write(sealed, 0, count)
        plain.fill(0, 0, filled)
        filled = 0
        index++
    }

    private fun discard(next: Phase = Phase.Aborted) {
        try {
            out.close()
        } catch (_: IOException) {
            // The file is deleted next; a failing close changes nothing.
        }
        pendingFile.delete()
        finish(next)
    }

    private fun finish(next: Phase) {
        phase = next
        key?.fill(0)
        key = null
        plain.fill(0)
        sealed.fill(0)
        onFinished(this)
    }
}

/** The writer handed out while the store is sealed for a wipe: accepts everything, keeps nothing. */
private class DroppingWriter : SealedMediaWriter {
    override fun write(buffer: ByteArray, offset: Int, size: Int) {
        if (offset < 0 || size < 0 || offset > buffer.size - size) throw IndexOutOfBoundsException()
    }

    override fun commit() = Unit

    override fun abort() = Unit

    override fun close() = Unit
}
