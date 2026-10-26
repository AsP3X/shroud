package de.corespace.shroud.core.contacts

import android.content.SharedPreferences
import de.corespace.shroud.core.crypto.ContactNameBookSeal
import de.corespace.shroud.core.crypto.ContactNameSeal
import de.corespace.shroud.core.crypto.IdentityKeyMaterial
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactNamesDto
import de.corespace.shroud.core.net.wire.LenientJson
import de.corespace.shroud.core.storage.RecordRead
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Shown until a mutual contact's seal opens on this phone. */
const val CONTACT_PLACEHOLDER = "Contact"

/**
 * Usernames this phone has opened, and a fingerprint of the name it last published to each
 * contact. The server never has them (security audit M9).
 *
 * Stored as `contact-names.sealed` in no-backup storage: JSON
 * `{"<owner id>": {"names": {"<peer id>": "<username>"}, "published": {"<peer id>": "<fingerprint>"}}}`
 * sealed by the Keystore key `shroud.contact-names.v1` **without** `unlockedDeviceRequired` (AFU), as
 * `NotificationNameCache` is: the roster also refreshes while the phone is locked. The Log Out /
 * removal wipe deletes the file and the key with every other sealed record and alias, and calls
 * [forget] on every path (`WipeHooks.forgetContactNames`, and `ContactsController.stop(wipe = true)`),
 * which also drops the copy in memory, so no later write puts the account's names back. While the
 * wipe has sealed storage, changes are dropped before they reach memory, as
 * `NotificationNameCache.rememberAll` drops them: a refresh still in flight cannot refill the book.
 *
 * Older builds kept the two maps as JSON strings in [legacy] (`contact.names.<owner>`,
 * `contact.names.published.<owner>`). The first read of the record takes them over where the record
 * has nothing, and the keys are deleted once a record holding them is written.
 *
 * Reads come from memory after the first load ([preload] at process start, else on the calling
 * thread — a file read and one Keystore open). Changes land in memory at once. One write at a time
 * is queued on [writer] (one thread): it takes a snapshot under the lock and seals and writes it
 * outside, so a caller never waits for a write. A record that cannot be read right now (phone locked
 * before its first unlock, a transient Keystore error) is never written over, and is tried again at
 * most every [RETRY_MS]: meanwhile the older build's names show, with this process's changes on
 * top, and only those changes are replayed onto the record once it reads. Never logs names or ids.
 * Thread-safe.
 */
class ContactNameBook(
    private val file: SealedFile,
    private val deleteKey: () -> Unit,
    private val seal: StorageSeal,
    private val legacy: SharedPreferences? = null,
    private val writer: Executor = Executors.newSingleThreadExecutor { Thread(it, "shroud-contact-names").apply { isDaemon = true } },
    /** Monotonic milliseconds, for spacing the reads of a record that could not be read. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Entry(
        val names: MutableMap<UUID, String> = LinkedHashMap(),
        val published: MutableMap<UUID, String> = LinkedHashMap(),
    )

    private val lock = Any()

    /**
     * By owner. Once [loaded]: the record and every change since. Before: what shows meanwhile, the
     * older build's names with [pending] on top. Guarded by [lock], like every field below.
     */
    private var entries = LinkedHashMap<UUID, Entry>()

    /** This process's changes made before the record could be read, replayed onto it once it reads. */
    private val pending = ArrayList<(MutableMap<UUID, Entry>) -> Boolean>()

    /** The record was read (or there is none), so [entries] may be written over it. */
    private var loaded = false

    /** When a read last found the record unreadable ([nowMs]); null when none did since the last success. */
    private var unreadableAt: Long? = null

    /** [entries] holds something the record does not. */
    private var dirty = false

    /** A write is queued on [writer] and has not taken its snapshot yet. */
    private var writeQueued = false

    /** Bumped by [forget]: a write whose snapshot is older removes what it wrote. */
    private var generation = 0L

    /** [legacy] keys to delete once a record holding their names is written. */
    private var legacyKeys = emptySet<String>()

    fun name(owner: UUID, peer: UUID): String? = synchronized(lock) {
        load()
        entries[owner]?.names?.get(peer)
    }

    fun remember(owner: UUID, peer: UUID, name: String) {
        if (!NAME.matches(name)) return
        change { it.getOrPut(owner) { Entry() }.names.put(peer, name) != name }
    }

    /** Every name this phone has, by lowercase peer id (for the shared [ContactNameBookSeal]). */
    fun all(owner: UUID): Map<String, String> = synchronized(lock) {
        load()
        entries[owner]?.names.orEmpty().mapKeys { Ids.wire(it.key) }
    }

    fun retain(owner: UUID, peers: Collection<UUID>) {
        val keep = peers.toSet()
        change(replayAlways = true) { map ->
            val entry = map[owner] ?: return@change false
            val namesDropped = entry.names.keys.retainAll(keep)
            val publishedDropped = entry.published.keys.retainAll(keep)
            namesDropped || publishedDropped
        }
    }

    fun published(owner: UUID, peer: UUID): String? = synchronized(lock) {
        load()
        entries[owner]?.published?.get(peer)
    }

    fun rememberPublished(owner: UUID, peer: UUID, fingerprint: String) {
        if (fingerprint.isEmpty()) return
        change { it.getOrPut(owner) { Entry() }.published.put(peer, fingerprint) != fingerprint }
    }

    /** Reads the record now (and takes over an older build's names), off the calling thread. */
    fun preload() {
        writer.execute { synchronized(lock) { load() } }
    }

    /**
     * Forgets every name: memory, the record, its Keystore key and an older build's keys (Log Out /
     * removal). Runs at once, on the calling thread. A write in flight removes what it wrote.
     */
    fun forget() {
        synchronized(lock) {
            entries = LinkedHashMap()
            pending.clear()
            loaded = false
            unreadableAt = null
            dirty = false
            generation++
            legacyKeys = emptySet()
            runCatching { file.delete() }
            runCatching { deleteKey() }
            runCatching { removeLegacy(legacyKeyNames()) }
        }
    }

    /** Waits until queued work ran (tests). */
    fun drain() {
        val done = java.util.concurrent.CountDownLatch(1)
        writer.execute { done.countDown() }
        done.await()
    }

    /**
     * Applies [edit] (true when it changed something) to memory and queues the write. Dropped while
     * [seal] is set (a wipe is running). Before the record could be read, an [edit] that changed what
     * shows is kept to be replayed onto it; a retain always is, since the record may hold peers that
     * do not show.
     */
    private fun change(replayAlways: Boolean = false, edit: (MutableMap<UUID, Entry>) -> Boolean) {
        if (seal.isSealed) return
        synchronized(lock) {
            if (seal.isSealed) return
            load()
            val edited = edit(entries)
            if (!loaded) {
                if (edited || replayAlways) pending += edit
            } else if (edited) {
                queueWrite()
            }
        }
    }

    /**
     * Under [lock]. Reads the record once: what it holds, then an older build's names where it has
     * none, then this process's [pending] changes. A record that cannot be read is tried again after
     * [RETRY_MS] at the earliest.
     */
    private fun load() {
        if (loaded) return
        val lastTry = unreadableAt
        if (lastTry != null && nowMs() - lastTry < RETRY_MS) return
        val stored: LinkedHashMap<UUID, Entry> = when (val record = file.readClassified()) {
            is RecordRead.Found -> try {
                decode(record.bytes)
            } finally {
                record.bytes.fill(0)
            }
            RecordRead.NotFound -> LinkedHashMap()
            RecordRead.DeviceLocked, RecordRead.Failed -> {
                // Shown meanwhile, never written: the older build's names, this process's changes on top.
                val shown = LinkedHashMap(readLegacy())
                for (edit in pending) edit(shown)
                entries = shown
                unreadableAt = nowMs()
                return
            }
        }
        unreadableAt = null
        val keys = legacyKeyNames()
        var added = false
        for ((owner, old) in readLegacy()) {
            if (fill(stored.getOrPut(owner) { Entry() }, old)) added = true
        }
        for (edit in pending) {
            if (edit(stored)) added = true
        }
        pending.clear()
        entries = stored
        loaded = true
        legacyKeys = keys
        if (added || keys.isNotEmpty()) queueWrite()
    }

    /** Puts [old]'s pairs into [entry] where it has none; true when that added something. */
    private fun fill(entry: Entry, old: Entry): Boolean {
        var added = false
        for ((peer, name) in old.names) if (entry.names.putIfAbsent(peer, name) == null) added = true
        for ((peer, fingerprint) in old.published) if (entry.published.putIfAbsent(peer, fingerprint) == null) added = true
        return added
    }

    /** Under [lock]. Marks [entries] unwritten and queues one write, unless one is queued already. */
    private fun queueWrite() {
        dirty = true
        if (!loaded || seal.isSealed || writeQueued) return
        writeQueued = true
        writer.execute { flush() }
    }

    /**
     * On [writer]. Takes a snapshot under [lock] and seals and writes it outside, so readers never
     * wait for the Keystore or the disk. When [forget] ran meanwhile, what was written (and a key the
     * write may have made) is removed again.
     */
    private fun flush() {
        val (snapshot, written, keys) = synchronized(lock) {
            writeQueued = false
            if (!dirty || !loaded || seal.isSealed) return
            dirty = false
            Triple(encode(entries), generation, legacyKeys)
        }
        val ok = try {
            file.write(snapshot)
            true
        } catch (_: Exception) {
            false
        }
        synchronized(lock) {
            if (generation != written) {
                runCatching { file.delete() }
                runCatching { deleteKey() }
                return
            }
            if (!ok) {
                dirty = true // The old record stays; the next change writes again.
                return
            }
            if (keys.isNotEmpty()) {
                runCatching { removeLegacy(keys) }
                legacyKeys = legacyKeys - keys
            }
        }
    }

    private fun legacyKeyNames(): Set<String> =
        legacy?.all?.keys.orEmpty().filter { it.startsWith(LEGACY_PREFIX) }.toSet()

    /** An older build's maps by owner. Keys that name no owner, and values that are no JSON object, are skipped. */
    private fun readLegacy(): Map<UUID, Entry> {
        val prefs = legacy ?: return emptyMap()
        val out = LinkedHashMap<UUID, Entry>()
        for ((key, stored) in prefs.all) {
            if (!key.startsWith(LEGACY_PREFIX)) continue
            val published = key.startsWith(LEGACY_PUBLISHED_PREFIX)
            val owner = Ids.parse(key.removePrefix(if (published) LEGACY_PUBLISHED_PREFIX else LEGACY_PREFIX)) ?: continue
            val json = (stored as? String)?.let { LenientJson.parseObject(it) } ?: continue
            val entry = out.getOrPut(owner) { Entry() }
            for ((id, value) in json) {
                val peer = LenientJson.uuidExact(id) ?: continue
                val text = LenientJson.string(value) ?: continue
                if (published) {
                    if (text.isNotEmpty()) entry.published[peer] = text
                } else if (NAME.matches(text)) {
                    entry.names[peer] = text
                }
            }
        }
        return out
    }

    private fun removeLegacy(keys: Set<String>) {
        val prefs = legacy ?: return
        if (keys.isEmpty()) return
        val edit = prefs.edit()
        keys.forEach { edit.remove(it) }
        edit.apply()
    }

    companion object {
        /** File name under `noBackupFilesDir`. */
        const val FILE_NAME = "contact-names.sealed"

        /** The AFU Keystore alias (`KeystoreSealer` without `unlockedDeviceRequired`). */
        const val KEY_ALIAS = "shroud.contact-names.v1"

        /** The earliest a record that could not be read is read again. */
        private const val RETRY_MS = 10_000L

        private const val LEGACY_PREFIX = "contact.names."
        private const val LEGACY_PUBLISHED_PREFIX = "contact.names.published."
        private const val NAMES = "names"
        private const val PUBLISHED = "published"
        private val NAME = Regex("^[a-z0-9_]{3,32}$")

        private fun encode(entries: Map<UUID, Entry>): ByteArray = LenientJson.encodeToBytes(
            buildJsonObject {
                for ((owner, entry) in entries.entries.sortedBy { Ids.wire(it.key) }) {
                    if (entry.names.isEmpty() && entry.published.isEmpty()) continue
                    put(
                        Ids.wire(owner),
                        buildJsonObject {
                            put(NAMES, pairs(entry.names))
                            put(PUBLISHED, pairs(entry.published))
                        },
                    )
                }
            },
        )

        private fun pairs(map: Map<UUID, String>): JsonObject = buildJsonObject {
            for ((peer, value) in map.entries.sortedBy { Ids.wire(it.key) }) put(Ids.wire(peer), JsonPrimitive(value))
        }

        /** Pairs that are not a canonical id and a valid name (or a non-empty fingerprint) are skipped. */
        private fun decode(bytes: ByteArray): LinkedHashMap<UUID, Entry> {
            val out = LinkedHashMap<UUID, Entry>()
            val json = LenientJson.parseObject(bytes) ?: return out
            for ((ownerId, value) in json) {
                val owner = LenientJson.uuidExact(ownerId) ?: continue
                val record = value as? JsonObject ?: continue
                val entry = Entry()
                (record[NAMES] as? JsonObject)?.forEach { (id, name) ->
                    val peer = LenientJson.uuidExact(id) ?: return@forEach
                    LenientJson.string(name)?.takeIf { NAME.matches(it) }?.let { entry.names[peer] = it }
                }
                (record[PUBLISHED] as? JsonObject)?.forEach { (id, fingerprint) ->
                    val peer = LenientJson.uuidExact(id) ?: return@forEach
                    LenientJson.string(fingerprint)?.takeIf { it.isNotEmpty() }?.let { entry.published[peer] = it }
                }
                out[owner] = entry
            }
            return out
        }
    }
}

/**
 * Fills contact rows from names this phone already has. The production exchange also opens
 * seals and publishes this account's name. Tests use [KEEP_LOCAL], which never talks to a peer.
 */
open class ContactNameExchange {
    open suspend fun apply(
        token: String,
        contacts: List<ContactItemDto>,
        previous: List<ContactItemDto>,
    ): List<ContactItemDto> = keepLocal(contacts, previous)

    /** Log Out / removal: forgets every name this phone keeps. */
    open fun forget() {}

    companion object {
        val KEEP_LOCAL = ContactNameExchange()

        fun keepLocal(contacts: List<ContactItemDto>, previous: List<ContactItemDto>): List<ContactItemDto> {
            val known = previous.associate { it.userId to it.username }
            return contacts.map { row ->
                val kept = known[row.userId]?.takeIf { it.isNotBlank() && it != CONTACT_PLACEHOLDER }
                row.copy(username = kept ?: row.username.ifBlank { CONTACT_PLACEHOLDER })
            }
        }
    }
}

/**
 * Opens each contact's seal and publishes this account's username, sealed to them.
 * A key change that has not been trusted is skipped.
 */
class MutualContactNames(
    private val book: ContactNameBook,
    private val owner: () -> UUID?,
    private val username: () -> String?,
    private val keys: () -> Pair<ByteArray, ByteArray>?,
    private val peerKey: suspend (UUID) -> ByteArray,
    private val publish: suspend (String, UUID, String) -> Unit,
    /** A copy of the history key while chats are unlocked; the caller's copy is wiped here. */
    private val historyKey: () -> ByteArray? = { null },
    private val fetchBook: suspend (String) -> ContactNamesDto = { ContactNamesDto() },
    private val storeBook: suspend (String, String, Long) -> Unit = { _, _, _ -> },
) : ContactNameExchange() {
    override suspend fun apply(
        token: String,
        contacts: List<ContactItemDto>,
        previous: List<ContactItemDto>,
    ): List<ContactItemDto> {
        val me = owner() ?: return keepLocal(contacts, previous)
        val still = contacts.map { it.userId }.toSet()
        for (row in previous) {
            if (row.userId in still && row.username.isNotBlank() && row.username != CONTACT_PLACEHOLDER) {
                book.remember(me, row.userId, row.username)
            }
        }
        book.retain(me, still)
        val name = username()?.takeIf { NAME.matches(it) }
        val material = keys()
        if (material != null) {
            val (privateKey, publicKey) = material
            try {
                for (contact in contacts) {
                    try {
                        val theirPublic = peerKey(contact.userId)
                        contact.sealedName?.let { sealed ->
                            ContactNameSeal.open(sealed, privateKey, publicKey, theirPublic)?.let {
                                book.remember(me, contact.userId, it)
                            }
                        }
                        if (name == null) continue
                        val fingerprint = fingerprint(name, theirPublic)
                        if (book.published(me, contact.userId) == fingerprint) continue
                        val sealed = ContactNameSeal.seal(name, privateKey, publicKey, theirPublic)
                        publish(token, contact.userId, sealed)
                        book.rememberPublished(me, contact.userId, fingerprint)
                    } catch (_: Exception) {
                        // No key yet, or a key change waiting to be trusted.
                    }
                }
            } finally {
                privateKey.fill(0)
            }
            syncBook(token, me, contacts.map { it.userId.toString() }.toSet())
        }
        return contacts.map { row ->
            row.copy(username = book.name(me, row.userId) ?: CONTACT_PLACEHOLDER)
        }
    }

    override fun forget() = book.forget()

    /**
     * Merges the book the account's devices share with this phone's names, and writes the union
     * back when this phone knows names the book lacks. A write another device beat is read again
     * and merged once more, so no device's names are lost. Offline, the names here still show.
     */
    private suspend fun syncBook(token: String, me: UUID, contactIds: Set<String>) {
        val key = historyKey() ?: return
        try {
            repeat(3) {
                val remote = try {
                    fetchBook(token)
                } catch (_: Exception) {
                    return
                }
                val remoteNames = ContactNameBookSeal.open(remote.sealed, me, key) ?: emptyMap()
                for ((id, known) in remoteNames) {
                    if (id !in contactIds) continue
                    val peer = UUID.fromString(id)
                    if (book.name(me, peer) == null) book.remember(me, peer, known)
                }
                val merged = (remoteNames + book.all(me)).filterKeys { it in contactIds }
                if (merged == remoteNames) return
                try {
                    storeBook(token, ContactNameBookSeal.seal(merged, me, key), remote.version)
                    return
                } catch (error: ApiError.Server) {
                    if (error.code != "VERSION_CONFLICT") return
                } catch (_: Exception) {
                    return
                }
            }
        } finally {
            key.fill(0)
        }
    }

    private fun fingerprint(name: String, theirPublic: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(name.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(theirPublic)
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    private companion object {
        val NAME = Regex("^[a-z0-9_]{3,32}$")
    }
}

/** Copies the agreement keys out of an unlocked identity. Null while chats are locked. */
fun IdentityKeyMaterial.agreementKeys(): Pair<ByteArray, ByteArray> =
    agreementPrivateKey.copyOf() to agreementPublic.copyOf()
