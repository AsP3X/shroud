package de.corespace.shroud.core.auth

import de.corespace.shroud.core.storage.PrefsFiles
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What survives the device wipe (`DeviceDataWipe.keptDefaultsKeys` / `keepsDefaultsKey` /
 * `keptPaths`, `ios/shroud/Services/Auth/DeviceDataWipe.swift:78-89, 226-232`; settings-lock
 * §14.3.2). The wipe works on locations, not on known stores, so everything not listed here goes:
 * keep this list short and make every entry something that is not the account's — the server
 * address, the public transcription weights and their bookkeeping, platform and library state
 * that breaks if deleted under a running process.
 *
 * [forApp] registers the kept rows of 00-plan §1.5 when the container is built; a package that
 * keeps more registers through `container.auth.wipeKeepList`. Registration and reads are thread-safe.
 */
class WipeKeepList {
    private class PrefsRule(val file: String, val key: String?, val prefix: String, val suffix: String)

    private val prefsRules = CopyOnWriteArrayList<PrefsRule>()
    private val paths = CopyOnWriteArrayList<String>()

    /** Keeps [key] of the SharedPreferences file [file]. */
    fun keepPrefsKey(file: String, key: String) {
        prefsRules += PrefsRule(file, key, "", "")
    }

    /**
     * Keeps every key of [file] that starts with [prefix] and ends with [suffix] (an empty [prefix]
     * and [suffix] keep the whole file), e.g. `transcription.` + `.ready` (`DeviceDataWipe.swift:87-89`).
     */
    fun keepPrefsKeyPrefix(file: String, prefix: String, suffix: String = "") {
        prefsRules += PrefsRule(file, null, prefix, suffix)
    }

    /**
     * Keeps a file or directory, prefix-matched on the absolute path: `no_backup/whisper` keeps the
     * weights under it, `no_backup/androidx.work.workdb` keeps the database and its `-wal`/`-shm`.
     */
    fun keepPath(file: File) {
        paths += file.absolutePath
    }

    /** Whether the wipe leaves [key] of [file] alone. */
    fun keepsPrefsKey(file: String, key: String): Boolean = prefsRules.any { rule ->
        rule.file == file && if (rule.key != null) rule.key == key else key.startsWith(rule.prefix) && key.endsWith(rule.suffix)
    }

    /** Whether [file] is kept: it is, or lies under, a kept path (prefix-matched). */
    fun keepsPath(file: File): Boolean {
        val path = file.absolutePath
        return paths.any { path.startsWith(it) }
    }

    /**
     * Whether the directory [dir] holds a kept path somewhere below it, so the wipe sweeps into it
     * instead of deleting it whole (`holdsProtected`, `DeviceDataWipe.swift:333-337`).
     */
    fun holdsKeptPath(dir: File): Boolean {
        val prefix = dir.absolutePath + File.separator
        return paths.any { it.startsWith(prefix) }
    }

    companion object {
        /** `ServerConfigurationStore.KEY`, the server this app talks to. */
        const val SERVER_CONFIGURATION_KEY = "shroud.server.configuration"

        /** Public Whisper weights under `no_backup/` (W2-WHISPER / W3-TRANSCRIPTION), kept across Log Out (P7). */
        const val WHISPER_DIR = "whisper"

        private const val WORK_DATABASE = "androidx.work.workdb"
        private const val WORK_PREFS = "androidx.work.util.preferences"

        /**
         * The app's keep-list: the kept rows of 00-plan §1.5 and the platform state of settings-lock
         * §14.3.2 — none of it the account's (`DeviceDataWipe.swift:78-89, 226-237`).
         */
        fun forApp(locations: WipeLocations): WipeKeepList = WipeKeepList().apply {
            // The server this app talks to.
            keepPrefsKey(PrefsFiles.SERVER, SERVER_CONFIGURATION_KEY)
            keepPrefsKey(PrefsFiles.WIPE, DeviceDataWipe.PENDING_KEY)
            // Device facts such as "notifications permission asked" survive Log Out (P6).
            keepPrefsKeyPrefix(PrefsFiles.DEVICE, "")
            // Which transcription model is installed and its readiness flags; the public weights.
            keepPrefsKey(PrefsFiles.VOICE, "transcription.model")
            keepPrefsKeyPrefix(PrefsFiles.VOICE, "transcription.", ".ready")
            keepPath(File(locations.noBackupDir, WHISPER_DIR))
            // Platform and library state that breaks if deleted under a running process: the code
            // cache, androidx.profileinstaller's markers, WorkManager's database and old preferences.
            keepPath(locations.codeCacheDir)
            keepPath(File(locations.filesDir, "profileinstaller"))
            keepPath(File(locations.filesDir, "profileInstalled"))
            keepPath(File(locations.noBackupDir, WORK_DATABASE))
            keepPath(File(locations.databasesDir, WORK_DATABASE))
            keepPrefsKeyPrefix(WORK_PREFS, "")
        }
    }
}
