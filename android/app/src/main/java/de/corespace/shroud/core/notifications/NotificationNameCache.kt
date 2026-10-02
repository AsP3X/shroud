package de.corespace.shroud.core.notifications

import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.wire.LenientJson
import de.corespace.shroud.core.storage.RecordRead
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Last-known usernames for notifications announced from the background connection while the chats
 * are locked (00-plan §1.4, §1.5, §1.7.10; W2-NOTIF work list 2026-10-01).
 *
 * The background connection (W3-PUSH) hears `message.new`, `contact.request`, … over the socket,
 * but with the chats locked nothing can be decrypted, and the event carries ids only. iOS gets the
 * name from the push (`e`, sealed for its extension); on this path there is no push, so the name
 * comes from here — the same name a push would carry (the server's username), never message content.
 *
 * Stored as `notification-names.sealed` in no-backup storage: JSON `{"<peer id>": "<username>"}`
 * sealed by the Keystore key `shroud.notification-names.v1` **without** `unlockedDeviceRequired`
 * (AFU, P3a): readable while the phone is locked, when these notifications arrive. Written only while
 * names are on ([namesOn], Show Sender): with names off [name] answers null and nothing is kept —
 * turning Show Sender off calls [deleteAll]. The Log Out / removal wipe deletes the file and the key
 * ([deleteAll]; the wipe's Keys step sweeps both anyway, 00-plan §1.5).
 *
 * [deleteKey] deletes that Keystore key ([KeystoreSealer.deleteKey][de.corespace.shroud.core.storage.KeystoreSealer.deleteKey]).
 *
 * Reads come from memory after the first load ([preload] at process start, else lazily on the
 * calling thread — a file read and one Keystore open). Writes run on [writer] (one thread) and are
 * dropped while [seal] is set (a wipe is running) or when nothing changed. Never logs names or
 * ids. Thread-safe.
 */
class NotificationNameCache(
    private val file: SealedFile,
    private val deleteKey: () -> Unit,
    private val seal: StorageSeal,
    private val namesOn: () -> Boolean,
    private val writer: Executor = Executors.newSingleThreadExecutor { Thread(it, "shroud-notification-names").apply { isDaemon = true } },
) {
    private val lock = Any()
    private var names: MutableMap<UUID, String>? = null

    /** The last-known username of [peer], or null (unknown, or names are off). */
    fun name(peer: UUID): String? {
        if (!namesOn()) return null
        return synchronized(lock) { loaded()[peer] }
    }

    /** Remembers [username] for [peer] (trimmed and cut like a push's name, [PushContents.clampName]). */
    fun remember(peer: UUID, username: String) = rememberAll(mapOf(peer to username))

    /**
     * Keeps this cache in step with the open chats, incoming requests and contacts (the interim
     * root's `feedNotificationNames`, and the shell's same map): chats first, then a request's
     * `user.username` when the card is present, then contacts win on the same id. An empty map is
     * not written. Repeats are skipped. Cancelling the [Job] stops the collection; nothing here
     * starts it — the shell calls it.
     */
    fun follow(
        scope: CoroutineScope,
        contacts: Flow<List<ContactItemDto>>,
        incomingRequests: Flow<List<ContactRequestDto>>,
        conversations: Flow<List<ConversationItemDto>>,
    ): Job = scope.launch {
        combine(contacts, incomingRequests, conversations) { roster, requests, chats ->
            buildMap {
                chats.forEach { put(it.peer.id, it.peer.username) }
                requests.forEach { request -> request.user?.let { put(request.fromUserId, it.username) } }
                roster.forEach { put(it.userId, it.username) }
            }
        }.distinctUntilChanged().collect { names ->
            if (names.isNotEmpty()) rememberAll(names)
        }
    }

    /**
     * Remembers every pair of [usernames] — the contacts and chats as they load (W2-INT wires
     * `ContactsController.contacts` and `MessagingController.conversations` here). Runs on [writer]:
     * merged into memory and written once, only when something changed.
     */
    fun rememberAll(usernames: Map<UUID, String>) {
        if (usernames.isEmpty() || !namesOn() || seal.isSealed) return
        val copy = usernames.toMap()
        writer.execute {
            // The write happens under the lock too, so [deleteAll] never races a write in flight
            // (which would put the names back on disk under a fresh key).
            synchronized(lock) {
                if (!namesOn() || seal.isSealed) return@execute
                val map = loaded()
                var changed = false
                for ((peer, raw) in copy) {
                    val name = PushContents.clampName(raw) ?: continue
                    if (map.put(peer, name) != name) changed = true
                }
                if (changed) runCatching { file.write(encode(map)) }
            }
        }
    }

    /** Reads the file now, so the first notification does not wait for the Keystore. */
    fun preload() {
        writer.execute { synchronized(lock) { loaded() } }
    }

    /**
     * Forgets every name: memory, the file and its Keystore key (Show Sender off; Log Out / removal
     * through `NotificationsController.forgetAccount`). Runs at once, on the calling thread, after a
     * write in flight finished.
     */
    fun deleteAll() {
        synchronized(lock) {
            names = mutableMapOf()
            runCatching { file.delete() }
            runCatching { deleteKey() }
        }
    }

    /** Waits until queued work ran (tests). */
    fun drain() {
        val done = java.util.concurrent.CountDownLatch(1)
        writer.execute { done.countDown() }
        done.await()
    }

    /**
     * Under [lock]. The names in memory, read from the file on first use. A record that cannot be
     * read right now (phone locked before its first unlock, a transient Keystore error) reads as
     * empty without being cached, and nothing is written over it.
     */
    private fun loaded(): MutableMap<UUID, String> {
        names?.let { return it }
        val read = when (val record = file.readClassified()) {
            is RecordRead.Found -> decode(record.bytes)
            RecordRead.NotFound -> mutableMapOf()
            RecordRead.DeviceLocked, RecordRead.Failed -> return Unreadable()
        }
        names = read
        return read
    }

    /** A stand-in map for a record that could not be read: takes puts, but they are never written. */
    private class Unreadable : LinkedHashMap<UUID, String>() {
        override fun put(key: UUID, value: String): String? = value
    }

    companion object {
        /** File name under `noBackupFilesDir` (00-plan §1.5). */
        const val FILE_NAME = "notification-names.sealed"

        /** The AFU Keystore alias (00-plan §1.5; `KeystoreSealer` without `unlockedDeviceRequired`). */
        const val KEY_ALIAS = "shroud.notification-names.v1"

        fun encode(names: Map<UUID, String>): ByteArray = LenientJson.encodeToBytes(
            buildJsonObject {
                for ((peer, name) in names.entries.sortedBy { Ids.wire(it.key) }) put(Ids.wire(peer), JsonPrimitive(name))
            },
        )

        /** Entries that are not a canonical id and a non-empty string are skipped. */
        fun decode(bytes: ByteArray): MutableMap<UUID, String> {
            val json = LenientJson.parseObject(bytes) ?: return mutableMapOf()
            val out = LinkedHashMap<UUID, String>()
            for ((key, value) in json) {
                val peer = LenientJson.uuidExact(key) ?: continue
                val name = PushContents.clampName(LenientJson.string(value)) ?: continue
                out[peer] = name
            }
            return out
        }
    }
}
