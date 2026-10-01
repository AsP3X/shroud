package de.corespace.shroud.core.storage

import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Decrypted media that has to exist as a file for a moment — a recording in progress, a Media3
 * Transformer output, a share buffer — lives in [dir] (`Context.cacheDir`) as `shroud-*`
 * (iOS `SensitiveTempFiles`, `ios/shroud/Services/Crypto/SensitiveTempFiles.swift:3-42`; crypto
 * spec §15; plan C25). Every writer removes its own file, but a crash or a kill skips that, so:
 *
 * - [prepareAtLaunch] deletes every `shroud-*` entry an earlier process left (`:16-24`; called from
 *   `KeysModule.onProcessStart`, iOS `RootView.swift:129`);
 * - [sweep] with [STALE_AGE_MS] runs when chats lock (`RootView.swift:313-317, 377`), deleting only
 *   entries not written to for that long, so a recording or playback still in progress keeps its file.
 *
 * iOS also sets `tmp/` to `completeUnlessOpen` (`:17-22`); Android has no per-directory class that
 * evicts on screen lock (crypto spec §15, R10), so plaintext files are kept rare and short-lived —
 * players read through decrypting data sources (plan C7) instead.
 *
 * Never logs file names (they can carry message ids).
 */
class SensitiveTempFiles(private val dir: File) {
    /**
     * A new empty file `shroud-<stem>-<random>.<extension>` in [dir] (e.g. `create("voice", "m4a")`).
     * [stem] is a short tag such as `voice`, `tx`, `export`; a leading `shroud-` is not repeated.
     * [extension] may be empty. The caller deletes the file when done.
     */
    fun create(stem: String, extension: String): File {
        val tag = stem.removePrefix(PREFIX).trim('-')
        require(tag.isNotEmpty() && TAG.matches(tag)) { "a temp file stem is [A-Za-z0-9_-]+" }
        val ext = extension.removePrefix(".")
        require(ext.isEmpty() || TAG.matches(ext)) { "a temp file extension is [A-Za-z0-9_-]+" }
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("could not create the temp directory")
        val name = PREFIX + tag + "-" + UUID.randomUUID() + if (ext.isEmpty()) "" else ".$ext"
        val file = File(dir, name)
        if (!file.createNewFile()) throw IOException("temp file exists")
        return file
    }

    /** Process start: deletes every `shroud-*` leftover (`SensitiveTempFiles.swift:16-24`). Never throws. */
    fun prepareAtLaunch() {
        sweep()
    }

    /**
     * Deletes `shroud-*` entries (files and directories) in [dir] (`SensitiveTempFiles.swift:26-41`).
     * With [olderThanMs], only those whose last modification is at least that long before [nowMs]
     * (an unknown modification time counts as old, as on iOS). Entries without the prefix are never
     * touched. Never throws.
     */
    fun sweep(olderThanMs: Long? = null, nowMs: Long = System.currentTimeMillis()) {
        val entries = try {
            dir.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return
        val cutoff = olderThanMs?.let { nowMs - it }
        for (entry in entries) {
            if (!entry.name.startsWith(PREFIX)) continue
            if (cutoff != null) {
                val modified = entry.lastModified()
                if (modified != 0L && modified > cutoff) continue
            }
            try {
                entry.deleteRecursively()
            } catch (_: SecurityException) {
                // Not ours to delete; nothing else to do.
            }
        }
    }

    companion object {
        const val PREFIX = "shroud-"

        /** The lock sweep's age: ten minutes (`RootView.swift:377`, `staleTempFileAge = 10 * 60`). */
        const val STALE_AGE_MS = 600_000L

        private val TAG = Regex("[A-Za-z0-9_-]+")
    }
}
