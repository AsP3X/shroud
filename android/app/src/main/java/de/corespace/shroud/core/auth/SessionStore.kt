package de.corespace.shroud.core.auth

import de.corespace.shroud.core.storage.RecordRead
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A signed-in account on this phone. The token is the only credential; it never expires. */
@Serializable
data class Session(
    val token: String,
    val userId: String,
    val username: String,
    val shareCode: String?,
    val deviceId: String,
) {
    /** Never prints the token. */
    override fun toString(): String = "Session(userId=$userId, deviceId=$deviceId)"
}

/**
 * The device this phone had on an account (`SessionStore` device anchor, `SessionStore.swift:85-134`).
 * A sign-in sends it so the server reuses the device row instead of spending one of the account's
 * five. Kept on a plain sign-out of the session, removed by the Log Out wipe.
 */
@Serializable
data class DeviceAnchor(val username: String, val deviceId: String)

/**
 * The record of `session.sealed`. `authFailures` is legacy: older builds persisted the 401 streak,
 * which now lives in memory only like iOS (settings-lock ST4, S5); it is read and ignored.
 */
@Serializable
private data class SessionRecord(val session: Session? = null, val authFailures: Int = 0)

/**
 * Session + device anchor, each sealed by the Keystore (`ios/shroud/Services/Auth/SessionStore.swift`;
 * settings-lock addendum SessionStore): `session.sealed` and `device-anchor.sealed` in no-backup
 * storage under the after-first-unlock key `shroud.session.v1` (00-plan §1.5), the Android side of
 * iOS's `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` items.
 *
 * One atomic file per record instead of iOS's five Keychain items (ST6). Disk and Keystore work:
 * call off the main thread.
 *
 * @param seal the process's wipe write stop: while a device wipe runs, [save] writes nothing (a
 *   refreshed profile must not recreate the file, and with it the session key, behind the wipe).
 */
class SessionStore(
    private val sessionFile: SealedFile,
    private val anchorFile: SealedFile,
    private val json: Json,
    private val seal: StorageSeal? = null,
) {
    private var record: SessionRecord
    private var unreadable: Boolean

    init {
        val (loaded, dead) = load()
        record = loaded
        unreadable = dead
    }

    /** The stored session (`load()`, `SessionStore.swift:26-45`), or null. */
    val session: Session? get() = record.session

    /**
     * Saves [session] and the anchor for its account (`save` + `saveDeviceAnchor`,
     * `SessionStore.swift:47-58, :102-106`). The anchor's name is normalised (ST3) and its write may
     * fail without failing the sign-in, as iOS ignores it (`try?`, ST2). Dropped while a wipe runs.
     */
    @Synchronized
    fun save(session: Session) {
        if (seal?.isSealed == true) return
        persist(SessionRecord(session = session))
        runCatching {
            val anchor = DeviceAnchor(SessionController.normalize(session.username), session.deviceId)
            anchorFile.write(json.encodeToString(DeviceAnchor.serializer(), anchor).toByteArray())
        }
    }

    /**
     * The anchored device for [username], if this phone signed into that account before
     * (`loadDeviceID(matchingUsername:)`, `SessionStore.swift:85-90`): compared trimmed and
     * case-insensitively; an empty name has no anchor.
     */
    fun anchorFor(username: String): String? {
        val needle = SessionController.normalize(username)
        if (needle.isEmpty()) return null
        val anchor = anchorFile.read()?.let { runCatching { json.decodeFromString(DeviceAnchor.serializer(), String(it)) }.getOrNull() }
        return anchor?.takeIf { it.username.trim().equals(needle, ignoreCase = true) }?.deviceId
    }

    /** Drops the session; keeps the anchor (`clear()`, `SessionStore.swift:68-77`). */
    @Synchronized
    fun clear() {
        record = SessionRecord()
        sessionFile.delete()
        unreadable = false
    }

    /** Drops the session and the anchor (`clear()` + `clearDeviceAnchor()`, the Log Out wipe). */
    @Synchronized
    fun wipe() {
        clear()
        anchorFile.delete()
    }

    /**
     * True only when no session file exists (`hasNoSession()`, `SessionStore.swift:111-120`; ST1).
     * [session] is null for "absent" and for "could not read it" alike, so nothing that deletes data
     * may treat `session == null` as signed out: `DeviceWipeController.finishInterruptedWipeIfNeeded`
     * asks this (and [isUnreadableForGood]) instead.
     */
    fun hasNoSession(): Boolean = !sessionFile.exists()

    /**
     * True when a session file exists but can never open again (its Keystore key is gone, the bytes
     * are not a record of it, or the plaintext is not a session) — ST1's "file that exists but will
     * not open": a forced sign-out, which the launch sweep clears. A transient read failure is not this.
     */
    @get:Synchronized
    val isUnreadableForGood: Boolean get() = unreadable && sessionFile.exists()

    /** Writes first, then adopts: a failed write leaves memory as it was on disk. */
    private fun persist(next: SessionRecord) {
        sessionFile.write(json.encodeToString(SessionRecord.serializer(), next).toByteArray())
        record = next
        unreadable = false
    }

    /** The record and whether an existing file is unreadable for good. */
    private fun load(): Pair<SessionRecord, Boolean> = when (val read = sessionFile.readClassified()) {
        is RecordRead.Found -> {
            val decoded = runCatching { json.decodeFromString(SessionRecord.serializer(), String(read.bytes)) }.getOrNull()
            read.bytes.fill(0)
            if (decoded != null) decoded to false else SessionRecord() to true
        }
        RecordRead.NotFound -> SessionRecord() to sessionFile.exists()
        RecordRead.DeviceLocked, RecordRead.Failed -> SessionRecord() to false
    }
}
