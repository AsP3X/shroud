package de.corespace.shroud.core.auth

import android.content.SharedPreferences
import de.corespace.shroud.core.keys.KeyMaterialWipe
import de.corespace.shroud.testing.FakeSharedPreferences
import java.io.File
import java.util.Collections

/** Preferences files of a fake app: every file ever opened is listed, like `shared_prefs/` plus the known names. */
class FakePrefsAccess : PrefsAccess {
    val files = LinkedHashMap<String, FakeSharedPreferences>()

    @Synchronized
    override fun fileNames(): Set<String> = files.keys.toSet()

    @Synchronized
    override fun open(name: String): SharedPreferences = files.getOrPut(name) { FakeSharedPreferences() }

    /** `file/key` of every stored key, sorted. */
    @Synchronized
    fun storedKeys(): Set<String> = files.flatMap { (name, prefs) -> prefs.keys.map { "$name/$it" } }.toSortedSet()

    fun put(file: String, key: String, value: Any) {
        val editor = open(file).edit()
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            else -> error("unsupported $value")
        }
        editor.commit()
    }
}

/** An AndroidKeyStore stand-in: a set of alias names. */
class FakeAliases(vararg initial: String) : KeyMaterialWipe.KeystoreAliases {
    val aliases: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet(initial.toList()))
    var listFails = false

    override fun list(): List<String> {
        if (listFails) throw IllegalStateException("keystore unavailable")
        return aliases.toList()
    }

    override fun delete(alias: String) {
        aliases -= alias
    }
}

/** Records the order of every hook the wipe calls. */
class RecordingWipeHooks : WipeHooks {
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())

    override fun haltWriters() {
        calls += "haltWriters"
    }

    override suspend fun stopMessaging(wipeDisk: Boolean) {
        calls += "stopMessaging($wipeDisk)"
    }

    override fun clearCalls() {
        calls += "clearCalls"
    }

    override suspend fun forgetPush() {
        calls += "forgetPush"
    }

    override fun forgetNotifications() {
        calls += "forgetNotifications"
    }

    override fun forgetAppearance() {
        calls += "forgetAppearance"
    }

    override fun lockCrypto(wipeStore: Boolean) {
        calls += "lockCrypto($wipeStore)"
    }

    fun count(call: String): Int = synchronized(calls) { calls.count { it == call } }
}

/** A [SystemWipe] whose leftovers a test can set. */
class FakeSystemWipe : SystemWipe {
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())

    @Volatile var stuck: List<DeviceDataWipe.Leftover> = emptyList()

    /** The Settings step's system part throws (an OEM's notification service, say). */
    @Volatile var clearFails = false

    override fun evictNetworkConnections() {
        calls += "evict"
    }

    override fun cancelAccountWork() {
        calls += "cancelWork"
    }

    override suspend fun clearSystemState() {
        calls += "clearSystem"
        if (clearFails) throw IllegalStateException("system service died")
    }

    override suspend fun leftovers(): List<DeviceDataWipe.Leftover> = stuck
}

/**
 * A data directory laid out like the app's after a while of use (settings-lock §18.2), with the
 * app's real keep-list ([WipeKeepList.forApp]).
 */
class WipeFixture(val root: File, val system: SystemWipe? = null) {
    val locations = WipeLocations.under(root)
    val prefs = FakePrefsAccess()
    val aliases = FakeAliases()
    val keepList = WipeKeepList.forApp(locations)
    val wipe = DeviceDataWipe(
        locations = locations,
        keyMaterial = KeyMaterialWipe(locations.keysDir, aliases),
        keystore = aliases,
        prefs = prefs,
        keepList = keepList,
        system = system,
    )

    fun write(path: String, bytes: Int = 16) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(bytes) { 7 })
    }

    fun exists(path: String): Boolean = File(root, path).exists()

    /** Account data in every place the app (or Android on its behalf) writes it. */
    fun seedAccount() {
        val user = "bc26c1ed22cb4a74b98f581c6faca09d"
        write("no_backup/shroud/messages/$user/roster.sealed")
        write("no_backup/shroud/messages/$user/threads/aa8c4d04.sealed")
        write("no_backup/shroud/plaintext/e09da208.sealed")
        write("no_backup/shroud/plaintext/bc159ab5.sealed")
        write("no_backup/shroud/media/46177c15.sealed", bytes = 4096)
        write("cache/shroud-play-x.mp4", bytes = 2048)
        write("cache/image_cache/0F1E", bytes = 512)
        write("cache/other.bin", bytes = 16)
        write("no_backup/shroud/voice/language-stats.sealed")
        write("files/export.txt")
        write("databases/foo.db")
        write("app_webview/Default/Cookies")
        write("no_backup/keys/identity.v1")
        write("no_backup/session.sealed")
        write("no_backup/device-anchor.sealed")
        // Not the account's: must survive.
        write("no_backup/whisper/ggml-base-q5_1.bin")
        write("code_cache/x")
        write("files/profileInstalled")
        write("no_backup/androidx.work.workdb")
        aliases.aliases += listOf("shroud.session.v1", "shroud.local.v1", "shroud.vault.wrap.1a2b3c4d")
        prefs.put("shroud.server", "shroud.server.configuration", "{}")
        prefs.put("shroud.voice", "transcription.model", "base")
        prefs.put("shroud.voice", "transcription.whispercpp.ready", "base")
        prefs.put("shroud.preferences", "security.autoLockDelay", "immediately")
        prefs.put("shroud.voice", "transcription.locale", "de-DE")
        prefs.put("shroud.appearance", "shroud.theme", "dark")
    }

    companion object {
        /** Every account path [seedAccount] writes. */
        val ACCOUNT_PATHS = listOf(
            "no_backup/shroud/messages",
            "no_backup/shroud/plaintext",
            "no_backup/shroud/media",
            "no_backup/shroud/voice",
            "cache/shroud-play-x.mp4",
            "cache/image_cache",
            "cache/other.bin",
            "files/export.txt",
            "databases/foo.db",
            "app_webview/Default",
            "no_backup/keys",
            "no_backup/session.sealed",
            "no_backup/device-anchor.sealed",
        )

        /** Every path that is not the account's. */
        val KEPT_PATHS = listOf(
            "no_backup/whisper/ggml-base-q5_1.bin",
            "code_cache/x",
            "files/profileInstalled",
            "no_backup/androidx.work.workdb",
        )
    }
}
