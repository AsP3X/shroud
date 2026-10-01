package de.corespace.shroud.core.messaging.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The message store's one serial disk writer (messaging-core §23.3, plan §1.4). iOS writes every
 * file synchronously on the main thread (`MessagingLocalRepository.swift:314-360`); here the caller
 * seals, and the file write itself is queued:
 *
 * - **Per-file coalescing.** Each write has a key (the file's path); a newer write of the same key
 *   replaces the pending one *and moves to the end*, so the files end up as if every write had run
 *   in order (a thread rewritten after a clean-up that removed it stays).
 * - **One writer.** Pending writes run on [scope] (a one-thread dispatcher) or, through [drain], on
 *   the caller — always under [lock], which the repository holds around every file operation of
 *   `shroud/messages/`. So [drain] returns only once every write queued before it is on disk.
 * - **Sealed before queueing.** Queued writes carry ciphertext, never the key: a chat lock that
 *   lands before the writer runs loses nothing (`flush()` before the key drops, plan §1.4).
 *
 * A failed write is dropped, as iOS drops it (`try?`, `LocalMessageStore.swift:449-463`): the next
 * save of that file writes it again. Never logs paths or contents.
 */
class LocalWriteQueue(private val lock: ReentrantLock, private val scope: CoroutineScope) {
    private val pending = LinkedHashMap<String, () -> Unit>() // guarded by lock
    private var scheduled = false // guarded by lock

    /** Writes that reached the disk (tests count them; never logged). */
    val completedWrites = AtomicInteger()

    /** True when nothing waits. */
    val isIdle: Boolean get() = lock.withLock { pending.isEmpty() }

    /** Queues [action] under [key], replacing a pending action of the same key and moving it to the end. */
    fun enqueue(key: String, action: () -> Unit) = lock.withLock {
        pending.remove(key)
        pending[key] = action
        if (!scheduled) {
            scheduled = true
            scope.launch {
                lock.withLock {
                    scheduled = false
                    runPending()
                }
            }
        }
    }

    /** Runs every pending write now, on the calling thread. */
    fun drain() = lock.withLock { runPending() }

    /** Forgets every pending write (the store is being deleted). */
    fun clear() = lock.withLock { pending.clear() }

    private fun runPending() {
        while (true) {
            val key = pending.keys.firstOrNull() ?: return
            val action = pending.remove(key) ?: continue
            try {
                action()
                completedWrites.incrementAndGet()
            } catch (_: Exception) {
                // Dropped; the next save of this file writes it again.
            }
        }
    }
}

/** Crash-safe file replacement for the sealed message files. */
object LocalFiles {
    /** Suffix of in-flight temp files; loads skip them, clean-ups delete them. */
    const val TMP_SUFFIX = ".tmp"

    /**
     * Replaces [file] with [bytes]: a temp file next to it, fsync, rename — a crash leaves the old
     * file or the new one, never half of one (iOS `.atomic`). The parent directory is created when a
     * wipe removed it (`LocalPlaintextCache.swift:80-81`). Each write has its own temp name, so two
     * writers of one file never share one; the last rename wins.
     */
    fun write(file: File, bytes: ByteArray) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("no parent directory")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("could not create a directory")
        val tmp = File(dir, file.name + "." + java.lang.Long.toHexString(ThreadLocalRandom.current().nextLong()) + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use {
                it.write(bytes)
                it.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("could not replace a file")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** The bytes of [file], or null when it is missing or unreadable. */
    fun read(file: File): ByteArray? = try {
        if (file.isFile) file.readBytes() else null
    } catch (_: IOException) {
        null
    }
}
