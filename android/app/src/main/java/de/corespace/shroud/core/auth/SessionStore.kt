package de.corespace.shroud.core.auth

import de.corespace.shroud.core.storage.SealedFile
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
)

/**
 * The device this phone had on an account (`SessionStore` device anchor). A sign-in sends it so
 * the server reuses the device row instead of spending one of the account's five. Kept on a plain
 * sign-out of the session, removed by the Log Out wipe.
 */
@Serializable
data class DeviceAnchor(val username: String, val deviceId: String)

@Serializable
private data class SessionRecord(val session: Session? = null, val authFailures: Int = 0)

/** Session + device anchor, each sealed by the Keystore (`SessionStore.swift`). */
class SessionStore(
    private val sessionFile: SealedFile,
    private val anchorFile: SealedFile,
    private val json: Json,
) {
    private var record: SessionRecord = load()

    val session: Session? get() = record.session

    /** Consecutive 401s from `/auth/me` (`SessionAuthFailureTracker`). */
    val authFailures: Int get() = record.authFailures

    /** Disk I/O and Keystore work: call off the main thread. */
    @Synchronized
    fun save(session: Session) {
        persist(SessionRecord(session = session, authFailures = 0))
        anchorFile.write(json.encodeToString(DeviceAnchor.serializer(), DeviceAnchor(session.username, session.deviceId)).toByteArray())
    }

    @Synchronized
    fun setAuthFailures(count: Int) {
        persist(record.copy(authFailures = count))
    }

    /** The anchored device for [username], if this phone signed into that account before. */
    fun anchorFor(username: String): String? {
        val anchor = anchorFile.read()?.let { runCatching { json.decodeFromString(DeviceAnchor.serializer(), String(it)) }.getOrNull() }
        return anchor?.takeIf { it.username.equals(username, ignoreCase = true) }?.deviceId
    }

    /** Drops the session; keeps the anchor. */
    @Synchronized
    fun clear() {
        record = SessionRecord()
        sessionFile.delete()
    }

    /** Drops the session and the anchor (Log Out wipe). */
    @Synchronized
    fun wipe() {
        clear()
        anchorFile.delete()
    }

    /** Writes first, then adopts: a failed write leaves memory as it was on disk. */
    private fun persist(next: SessionRecord) {
        sessionFile.write(json.encodeToString(SessionRecord.serializer(), next).toByteArray())
        record = next
    }

    private fun load(): SessionRecord =
        sessionFile.read()?.let { runCatching { json.decodeFromString(SessionRecord.serializer(), String(it)) }.getOrNull() }
            ?: SessionRecord()
}
