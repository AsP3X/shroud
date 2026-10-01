package de.corespace.shroud.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import de.corespace.shroud.core.keys.KeyMaterialWipe
import de.corespace.shroud.core.media.ByteCountLabel
import de.corespace.shroud.core.storage.PrefsFiles
import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * Persists "a device wipe is under way" the moment the session ends, so a kill before the wipe
 * finishes is completed at the next launch (`markSessionEnded()`, `SessionController.swift:193-200`).
 * [DeviceDataWipe] is the real one; tests pass a fake, so they never set the app's marker.
 */
fun interface WipePendingMarker {
    fun markPending()

    companion object {
        /** Marks nothing (unit tests of the session alone). */
        val None = WipePendingMarker { }
    }
}

/**
 * Where the app keeps data (`DeviceDataWipe.Locations`, `DeviceDataWipe.swift:46-66`; settings-lock
 * §14.3). Tests point it at a temporary directory ([under]).
 *
 * @property dataDir the app's data directory: `shared_prefs/`, `databases/`, `app_*` and the rest live in it.
 */
data class WipeLocations(
    val dataDir: File,
    val filesDir: File,
    val noBackupDir: File,
    val cacheDir: File,
    val databasesDir: File,
    val codeCacheDir: File = File(dataDir, "code_cache"),
    val externalFiles: List<File> = emptyList(),
    val externalCache: List<File> = emptyList(),
) {
    /** `noBackupFilesDir/shroud/` — the sealed message, media and voice stores (00-plan §1.5). */
    val shroudDir: File get() = File(noBackupDir, "shroud")
    val messagesDir: File get() = File(shroudDir, "messages")
    val plaintextDir: File get() = File(shroudDir, "plaintext")
    val mediaDir: File get() = File(shroudDir, "media")

    /** Transcription language statistics (W3-TRANSCRIPTION), named so a narrower sweep cannot miss them (`DeviceDataWipe.swift:166-168`). */
    val voiceDir: File get() = File(shroudDir, "voice")

    /** `noBackupFilesDir/keys/` — identity, vault record, ratchets, sender tags, peer pins (W1-KEYS). */
    val keysDir: File get() = File(noBackupDir, "keys")

    /** `app_*` directories of the data directory (WebView and other platform state written for the app). */
    fun appDirs(): List<File> = dataDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("app_") }.orEmpty()

    companion object {
        /** The app's real locations. External directories that are not mounted are left out. */
        fun of(context: Context): WipeLocations = WipeLocations(
            dataDir = context.dataDir,
            filesDir = context.filesDir,
            noBackupDir = context.noBackupFilesDir,
            cacheDir = context.cacheDir,
            databasesDir = File(context.dataDir, "databases"),
            codeCacheDir = context.codeCacheDir,
            externalFiles = context.getExternalFilesDirs(null).filterNotNull(),
            externalCache = context.externalCacheDirs.filterNotNull(),
        )

        /** The layout of a data directory under [root] (`files/`, `no_backup/`, `cache/`, `databases/`, `code_cache/`), for tests. */
        fun under(root: File): WipeLocations = WipeLocations(
            dataDir = root,
            filesDir = File(root, "files"),
            noBackupDir = File(root, "no_backup"),
            cacheDir = File(root, "cache"),
            databasesDir = File(root, "databases"),
        )
    }
}

/** The app's SharedPreferences files, behind an interface so JVM tests use fakes. */
interface PrefsAccess {
    /** Every preferences file of the app: those on disk and those this process opened. */
    fun fileNames(): Set<String>

    fun open(name: String): SharedPreferences
}

/**
 * What the device keeps for the app outside its files (`touchesSystemState`,
 * `DeviceDataWipe.swift:96, 146, 174-183, 206-213`). Null in tests.
 */
interface SystemWipe {
    /** Media step: OkHttp keeps no response cache (`cache(null)`), so only its pooled connections go. */
    fun evictNetworkConnections()

    /** Settings step, before the file sweep: cancel the scheduled jobs that carry account data; WorkManager's own database is kept. */
    fun cancelAccountWork()

    /** Settings step: posted notifications (the badge follows), a phrase on the clipboard. */
    suspend fun clearSystemState()

    /** Verify: what the system still shows of the account (active notifications). */
    suspend fun leftovers(): List<DeviceDataWipe.Leftover>
}

/**
 * Removes everything Shroud keeps on this device for the account, and proves it
 * (`ios/shroud/Services/Auth/DeviceDataWipe.swift`; settings-lock §14.3).
 *
 * It wipes **locations**, not the stores that exist today: every file in the app's directories goes
 * except a short keep-list ([WipeKeepList]), every Keystore alias goes, and every SharedPreferences
 * key except the kept ones. [leftovers] scans the same places again, so a store someone adds later
 * shows up as a leftover instead of quietly surviving a Log Out (`DeviceDataWipe.swift:12-19`).
 *
 * Android locations per step (settings-lock §14.3 table):
 * - Messages: `no_backup/shroud/messages/`, `no_backup/shroud/plaintext/`.
 * - Media: `no_backup/shroud/media/`, everything in `cache/` (sensitive temp files, shares) and the
 *   external cache directories; OkHttp's connection pool.
 * - Keys: [KeyMaterialWipe] (`no_backup/keys/**` and every `shroud.` alias), every sealed record at the
 *   no-backup root (`session.sealed`, `device-anchor.sealed`, `call-secrets.sealed`, `unifiedpush.sealed`,
 *   `notification-names.sealed`, …) and every other Keystore alias of the app. Deleting never needs
 *   authentication or an unlocked phone.
 * - Settings: every SharedPreferences key not kept — removed through the API and **committed**, never by
 *   deleting the XML (`SharedPreferencesImpl` would write its cached copy back); the voice statistics;
 *   every other file under `files/`, `no_backup/`, `databases/`, `app_*/` and the external files
 *   directories; posted notifications and a phrase on the clipboard ([SystemWipe]).
 *
 * Blocking file and Keystore work: call it off the main thread (the wipe controller uses its IO
 * dispatcher). Never logs names or contents.
 */
class DeviceDataWipe(
    private val locations: WipeLocations,
    private val keyMaterial: KeyMaterialWipe,
    private val keystore: KeyMaterialWipe.KeystoreAliases,
    private val prefs: PrefsAccess,
    private val keepList: WipeKeepList,
    private val system: SystemWipe? = null,
) : WipePendingMarker {
    /** What was stored when the wipe began — the numbers the overlay reports (`DeviceDataWipe.swift:31-38`). */
    data class Inventory(
        val messages: Int = 0,
        val mediaFiles: Int = 0,
        val mediaBytes: Long = 0,
        val keys: Int = 0,
        val settings: Int = 0,
    ) {
        /** "37 files · 18.2 MB", "1 file" (`mediaSummary`, `DeviceDataWipe.swift:380-388`; settings-lock §14.4). */
        val mediaSummary: String get() = mediaSummary { fileSize(it) }

        /** [mediaSummary] with another byte formatter (a fixed locale in tests). */
        fun mediaSummary(formatBytes: (Long) -> String): String {
            val files = if (mediaFiles == 1) "1 file" else "$mediaFiles files"
            return if (mediaBytes > 0) "$files · ${formatBytes(mediaBytes)}" else files
        }
    }

    /** Something [leftovers] found, and the step that should have removed it (`DeviceDataWipe.swift:40-44`). */
    data class Leftover(val step: WipeStep, val label: String)

    // ---- Pending marker (`DeviceDataWipe.swift:109-121`) ----

    /**
     * Set before the first deletion, cleared once [leftovers] comes back empty. A launch that finds
     * it finishes the wipe (the app was killed mid-way). Prefs `shroud.wipe`, key
     * `shroud.deviceWipe.pending`; it survives the Settings step.
     */
    val isPending: Boolean get() = prefs.open(PrefsFiles.WIPE).getBoolean(PENDING_KEY, false)

    /** Written with `commit()`: on disk before this returns (a kill right after must still find it). */
    override fun markPending() {
        prefs.open(PrefsFiles.WIPE).edit(commit = true) { putBoolean(PENDING_KEY, true) }
    }

    fun clearPending() {
        prefs.open(PrefsFiles.WIPE).edit(commit = true) { remove(PENDING_KEY) }
    }

    // ---- Inventory (`DeviceDataWipe.swift:125-134`) ----

    fun inventory(): Inventory {
        val media = mediaFiles()
        return Inventory(
            messages = messageFiles().size,
            mediaFiles = media.size,
            mediaBytes = media.sumOf { it.second },
            keys = (aliases() ?: emptyList()).size + keyFiles().size,
            settings = removablePrefsKeys().sumOf { it.second.size },
        )
    }

    // ---- Steps ----

    /** Messages: the sealed thread snapshots, rosters, indexes and plaintext cache (`:138-141`). */
    fun wipeMessages() {
        messageRoots().forEach(::remove)
    }

    /** Photos, videos, voice notes and anything cached (`:143-150`). */
    fun wipeMedia() {
        system?.evictNetworkConnections()
        remove(locations.mediaDir)
        sweep(locations.cacheDir)
        locations.externalCache.forEach(::sweep)
    }

    /** Keys: key records, sealed root records and every Keystore alias (`:152-158`). Each deletion is tried even if another failed. */
    fun wipeKeys() {
        runCatching { keyMaterial.wipeAll() }
        rootSealedRecords().forEach { runCatching { deleteTree(it) } }
        for (alias in aliases().orEmpty()) runCatching { keystore.delete(alias) }
    }

    /**
     * Settings and traces (`:160-183`): every preferences key not kept, the voice statistics, every
     * other file the app wrote, then the system's state.
     */
    suspend fun wipeSettings() {
        for ((name, keys) in removablePrefsKeys()) {
            // Committed, so the verify pass and a kill right after see the keys gone.
            prefs.open(name).edit(commit = true) { keys.forEach { remove(it) } }
        }
        remove(locations.voiceDir)
        system?.cancelAccountWork()
        settingsRoots().forEach(::sweep)
        system?.clearSystemState()
    }

    /** The four deleting steps again — verify does this once before it gives up (`:185-192`). */
    suspend fun wipeEverything() {
        wipeMessages()
        wipeMedia()
        wipeKeys()
        wipeSettings()
    }

    // ---- Verify (`DeviceDataWipe.swift:196-215`) ----

    /** Everything that would contradict "nothing of the account is left". Empty means clean. */
    suspend fun leftovers(): List<Leftover> {
        val found = ArrayList<Leftover>()
        if (messageFiles().isNotEmpty()) found += Leftover(WipeStep.Messages, LABEL_MESSAGES)
        if (mediaFiles().isNotEmpty()) found += Leftover(WipeStep.Media, LABEL_MEDIA)
        // An alias list that cannot be read counts as "still here", not as clean (`keychainItemCount() != 0`).
        val aliases = aliases()
        if (aliases == null || aliases.isNotEmpty() || keyFiles().isNotEmpty() || keyMaterial.leftovers().isNotEmpty()) {
            found += Leftover(WipeStep.Keys, LABEL_KEYS)
        }
        if (removablePrefsKeys().isNotEmpty() || settingsFiles().isNotEmpty()) found += Leftover(WipeStep.Settings, LABEL_SETTINGS)
        system?.let { found += it.leftovers() }
        return found
    }

    // ---- Where things are ----

    private fun messageRoots(): List<File> = listOf(locations.messagesDir, locations.plaintextDir)

    private fun messageFiles(): List<File> = messageRoots().flatMap { regularFiles(it) }.map { it.first }

    /** Sealed media, everything in the cache directories (`mediaFiles()`, `:256-259`). */
    private fun mediaFiles(): List<Pair<File, Long>> =
        (listOf(locations.mediaDir, locations.cacheDir) + locations.externalCache).flatMap { regularFiles(it) }

    private fun keyFiles(): List<File> = regularFiles(locations.keysDir).map { it.first } + rootSealedRecords()

    /** `<name>.sealed` records at the no-backup root, and the temp files of their atomic writes. */
    private fun rootSealedRecords(): List<File> =
        children(locations.noBackupDir).filter { !it.isDirectory && !keepList.keepsPath(it) && isSealedRecordName(it.name) }

    /** Directories whose every file is a "setting" unless it is a message, media or key file (`settingsFiles()`, `:261-268`). */
    private fun settingsRoots(): List<File> =
        listOf(locations.filesDir, locations.noBackupDir, locations.databasesDir) + locations.appDirs() + locations.externalFiles

    private fun settingsFiles(): List<File> {
        val accounted = (messageFiles() + mediaFiles().map { it.first } + keyFiles()).map { it.absolutePath }.toHashSet()
        return settingsRoots().flatMap { regularFiles(it) }.map { it.first }.filter { it.absolutePath !in accounted }
    }

    /** Per preferences file, the keys the wipe removes (`removableDefaultsKeys()`, `:270-272`). */
    private fun removablePrefsKeys(): List<Pair<String, List<String>>> = prefs.fileNames().sorted().mapNotNull { name ->
        val keys = prefs.open(name).all.keys.filter { !keepsPrefsKey(name, it) }
        if (keys.isEmpty()) null else name to keys
    }

    private fun keepsPrefsKey(file: String, key: String): Boolean =
        (file == PrefsFiles.WIPE && key == PENDING_KEY) || keepList.keepsPrefsKey(file, key)

    /** Every alias of the app, or null when the Keystore cannot be listed. */
    private fun aliases(): List<String>? = runCatching { keystore.list() }.getOrNull()

    // ---- Files (`DeviceDataWipe.swift:274-337`) ----

    private fun children(dir: File): List<File> = dir.listFiles()?.toList().orEmpty()

    private fun isSymlink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    private fun isDirectory(file: File): Boolean = !isSymlink(file) && file.isDirectory

    /** Every regular file under [root] the wipe owns, with its size; kept paths are skipped. */
    private fun regularFiles(root: File): List<Pair<File, Long>> {
        if (keepList.keepsPath(root) || !(root.exists() || isSymlink(root))) return emptyList()
        if (!isDirectory(root)) return listOf(root to root.length())
        return children(root).flatMap { regularFiles(it) }
    }

    /** Deletes [file]; a directory that holds a kept path is swept into instead (`remove`, `:310-317`). */
    private fun remove(file: File) {
        if (keepList.keepsPath(file)) return
        if (isDirectory(file) && keepList.holdsKeptPath(file)) {
            sweep(file)
            return
        }
        deleteTree(file)
    }

    /** Deletes everything inside [dir] except kept paths; [dir] itself stays (`sweep`, `:319-331`). */
    private fun sweep(dir: File) {
        for (child in children(dir)) {
            if (keepList.keepsPath(child)) continue
            if (isDirectory(child) && keepList.holdsKeptPath(child)) sweep(child) else deleteTree(child)
        }
    }

    /** Deletes a file or a directory tree without following symbolic links. */
    private fun deleteTree(file: File) {
        if (isDirectory(file)) children(file).forEach(::deleteTree)
        file.delete()
    }

    companion object {
        /** `DeviceDataWipe.pendingKey` (`DeviceDataWipe.swift:81`), in prefs [PrefsFiles.WIPE]. */
        const val PENDING_KEY = "shroud.deviceWipe.pending"

        /**
         * WorkManager tag of every job that carries account data: the wipe cancels these (and prunes
         * finished jobs) but never the job it may itself run in — the removal wake's worker must not
         * carry it.
         */
        const val ACCOUNT_WORK_TAG = "shroud.account"

        const val LABEL_MESSAGES = "messages"
        const val LABEL_MEDIA = "media and cached files"
        const val LABEL_KEYS = "encryption keys"
        const val LABEL_SETTINGS = "settings"
        const val LABEL_NOTIFICATIONS = "notifications"

        private fun isSealedRecordName(name: String): Boolean = name.endsWith(".sealed") || name.contains(".sealed.")

        /**
         * A byte count as iOS `ByteCountFormatter.string(fromByteCount:countStyle: .file)` writes it
         * (00-plan C34): below 1 000 bytes in bytes ("1 byte", "512 bytes" — the default formatter
         * allows that unit), from there [ByteCountLabel.format] (Foundation's half-up rounding and
         * unit growth: 2 500 B → "3 KB", 999 999 B → "1 MB").
         */
        fun fileSize(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
            bytes == 1L -> "1 byte"
            bytes < 1_000 -> "$bytes bytes"
            else -> ByteCountLabel.format(bytes, locale)
        }
    }
}
