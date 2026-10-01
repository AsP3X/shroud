package de.corespace.shroud.core.messaging.local

import de.corespace.shroud.core.crypto.LocalHistoryCrypto
import de.corespace.shroud.core.keys.LocalNames
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.serialization.KSerializer
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Encrypted on-device message store: a **roster** file plus one **thread** file per peer, so one new
 * message never rewrites every other chat (iOS `LocalMessageStore`,
 * `ios/shroud/Services/Messaging/LocalMessageStore.swift:4-480`; messaging-core §22.2, §22.4).
 *
 * Layout under [root] (`Context.noBackupFilesDir`, plan §1.5), every name keyed by [LocalNames]
 * (plan C10; iOS uses the plain ids, `:193-221`):
 *
 * ```
 * shroud/messages/<name user:id>/roster.sealed        Roster
 * shroud/messages/<name user:id>/reactions.sealed     ReactionCursorsFile
 * shroud/messages/<name user:id>/annotations.sealed   AnnotationIndexFile (Android, D5)
 * shroud/messages/<name user:id>/threads/<name thread:peer>.sealed   ThreadFile (peer id inside)
 * ```
 *
 * Each file is one [LocalHistoryCrypto] blob under the `MessagesSnapshot` subkey with its relative
 * path as AAD ([LocalStoreKeys]). While chats are locked nothing here can be named, read or written:
 * loads return null, seals return null. iOS's legacy `snapshot.sealed`/`snapshot.json` migration
 * (`:417-445`) has no Android counterpart.
 *
 * Not thread-safe by itself: [MessagingLocalRepository] serialises every call under its I/O lock.
 */
class LocalMessageStore(
    private val root: File,
    private val keys: LocalStoreKeys,
    private val clock: AppClock,
    private val storageSeal: StorageSeal,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /**
     * Roster + threads in memory (`LocalMessageStore.Snapshot`, `:25-35`). [threads] is keyed by the
     * thread key (Notes: [NOTES_PEER_ID]); [unreadByPeer] by lower-case peer id, as on disk.
     */
    data class Snapshot(
        val conversations: List<StoredConversation> = emptyList(),
        val contacts: List<CachedContact> = emptyList(),
        val incomingRequests: List<CachedContactRequest> = emptyList(),
        val threads: Map<UUID, List<StoredMessage>> = emptyMap(),
        val unreadByPeer: Map<String, Int> = emptyMap(),
    ) {
        fun roster(): Roster = Roster(
            conversations = conversations,
            contacts = contacts,
            incomingRequests = incomingRequests,
            unreadByPeer = unreadByPeer,
        )

        override fun toString(): String = "Snapshot(conversations=${conversations.size}, threads=${threads.size})"
    }

    /** A file and the AAD its blob is bound to (its path relative to [root], `/`-separated). */
    class Location(val file: File, val aad: String)

    /** A sealed file ready to be written; carries ciphertext only. */
    class SealedWrite(val location: Location, val bytes: ByteArray)

    /** `shroud/messages`: every account's store (the wipe deletes it by location, settings-lock §14.3). */
    val messagesDir: File get() = File(root, MESSAGES)

    // ---- Locations (null while locked) ----

    /** `shroud/messages/<name user:id>` (`directoryURL`, `:193-202`). */
    fun userDirectory(userId: UUID): File? = userPath(userId)?.let { File(root, it) }

    fun rosterLocation(userId: UUID): Location? = userPath(userId)?.let { location("$it/$ROSTER") }

    fun reactionCursorsLocation(userId: UUID): Location? = userPath(userId)?.let { location("$it/$REACTIONS") }

    fun annotationsLocation(userId: UUID): Location? = userPath(userId)?.let { location("$it/$ANNOTATIONS") }

    fun threadLocation(peerId: UUID, userId: UUID): Location? {
        val user = userPath(userId) ?: return null
        val name = keys.name(LocalNames.Kind.THREAD, peerId) ?: return null
        return location("$user/$THREADS/$name$SEALED")
    }

    private fun userPath(userId: UUID): String? = keys.name(LocalNames.Kind.USER, userId)?.let { "$MESSAGES/$it" }

    private fun location(relative: String) = Location(File(root, relative), relative)

    // ---- Load ----

    /**
     * Roster + every thread (`load`, `:233-266`): null while locked. A missing roster (or one that
     * does not open) is an empty install — an empty snapshot, threads not read (`:237-240`). A thread
     * file that does not open or decode is skipped; it stays on disk until the next full save
     * removes it (`MessagingLocalRepository.swift:174-175`).
     */
    fun load(userId: UUID): Snapshot? {
        val rosterAt = rosterLocation(userId) ?: return null
        val roster = decode(rosterAt, Roster.serializer()) ?: return if (keys.isUnlocked) Snapshot() else null
        val user = userPath(userId) ?: return null
        val threads = LinkedHashMap<UUID, List<StoredMessage>>()
        val files = File(root, "$user/$THREADS").listFiles().orEmpty().filter { it.isFile && it.name.endsWith(SEALED) }
        for (file in files.sortedBy { it.name }) {
            val thread = decode(location("$user/$THREADS/${file.name}"), ThreadFile.serializer()) ?: continue
            // The AAD already binds the file to its name; a name that is not this peer's is not ours.
            if (keys.name(LocalNames.Kind.THREAD, thread.peerId) + SEALED != file.name) continue
            threads[thread.peerId] = thread.messages
        }
        return Snapshot(roster.conversations, roster.contacts, roster.incomingRequests, threads, roster.unreadByPeer)
    }

    /** `loadRoster` (`:288-290`): null while locked, when missing or when it does not open. */
    fun loadRoster(userId: UUID): Roster? = rosterLocation(userId)?.let { decode(it, Roster.serializer()) }

    /** `loadThread` (`:298-305`). */
    fun loadThread(peerId: UUID, userId: UUID): List<StoredMessage>? {
        val at = threadLocation(peerId, userId) ?: return null
        val thread = decode(at, ThreadFile.serializer()) ?: return null
        return thread.messages.takeIf { thread.peerId == peerId }
    }

    /**
     * Reaction catch-up cursors (`loadReactionCursors`, `:277-282`): an empty file object when there
     * is none yet; null while locked or when the file exists but does not open — never an empty map
     * a later save would write over every chat's cursor with (`MessagingLocalRepository.swift:364-366`).
     */
    fun loadReactionCursors(userId: UUID): ReactionCursorsFile? {
        val at = reactionCursorsLocation(userId) ?: return null
        if (!at.file.exists()) return ReactionCursorsFile()
        return decode(at, ReactionCursorsFile.serializer())
    }

    /**
     * The annotation index (D5): null while locked; empty when missing or unreadable — it only
     * widens purges, so starting over loses nothing that is still shown.
     */
    fun loadAnnotations(userId: UUID): AnnotationIndexFile? {
        val at = annotationsLocation(userId) ?: return null
        if (!at.file.exists()) return AnnotationIndexFile()
        return decode(at, AnnotationIndexFile.serializer()) ?: AnnotationIndexFile()
    }

    // ---- Seal (on the caller) and write (on the writer) ----

    /** The roster stamped now (`saveRoster`, `:292-296`), sealed; null while locked. */
    fun sealRoster(roster: Roster, userId: UUID): SealedWrite? =
        rosterLocation(userId)?.let { encode(it, Roster.serializer(), roster.copy(updatedAt = clock.now())) }

    /** One peer's thread (`saveThread`, `:307-321`), sealed; null while locked. */
    fun sealThread(peerId: UUID, messages: List<StoredMessage>, userId: UUID): SealedWrite? =
        threadLocation(peerId, userId)?.let {
            encode(it, ThreadFile.serializer(), ThreadFile(peerId = peerId, messages = messages, updatedAt = clock.now()))
        }

    fun sealReactionCursors(cursors: ReactionCursorsFile, userId: UUID): SealedWrite? =
        reactionCursorsLocation(userId)?.let { encode(it, ReactionCursorsFile.serializer(), cursors) }

    fun sealAnnotations(index: AnnotationIndexFile, userId: UUID): SealedWrite? =
        annotationsLocation(userId)?.let { encode(it, AnnotationIndexFile.serializer(), index) }

    /** Writes a sealed file atomically; dropped while a wipe runs ([StorageSeal]). */
    fun write(sealed: SealedWrite) {
        if (storageSeal.isSealed) return
        LocalFiles.write(sealed.location.file, sealed.bytes)
    }

    /** Writes one thread now (`saveThread`). */
    fun saveThread(peerId: UUID, messages: List<StoredMessage>, userId: UUID) {
        sealThread(peerId, messages, userId)?.let(::write)
    }

    /** Writes the roster now (`saveRoster`). */
    fun saveRoster(roster: Roster, userId: UUID) {
        sealRoster(roster, userId)?.let(::write)
    }

    fun saveReactionCursors(cursors: ReactionCursorsFile, userId: UUID) {
        sealReactionCursors(cursors, userId)?.let(::write)
    }

    /** Roster + every thread now, then the thread files of peers no longer present (`save`, `:323-340`). */
    fun save(snapshot: Snapshot, userId: UUID) {
        saveRoster(snapshot.roster(), userId)
        for ((peer, messages) in snapshot.threads) saveThread(peer, messages, userId)
        threadCleanup(snapshot.threads.keys, userId)?.invoke()
    }

    /**
     * The clean-up of `removeThreads(notIn:)` (`:342-361`), its names computed now (they need the
     * key) and run later: deletes every file in `threads/` that is not one of [wantedPeers]' — and
     * leftover temp files. Null while locked.
     */
    fun threadCleanup(wantedPeers: Set<UUID>, userId: UUID): (() -> Unit)? {
        val user = userPath(userId) ?: return null
        val wanted = HashSet<String>()
        for (peer in wantedPeers) wanted += (keys.name(LocalNames.Kind.THREAD, peer) ?: return null) + SEALED
        val dir = File(root, "$user/$THREADS")
        return {
            if (!storageSeal.isSealed) {
                dir.listFiles().orEmpty().filter { it.isFile && it.name !in wanted }.forEach { it.delete() }
            }
        }
    }

    // ---- Clear ----

    /**
     * Deletes one account's directory (`clear(userID:)`, `:363-365`). Its name needs the key: while
     * chats are locked every account's store goes (a leftover of another account is wiped anyway,
     * web-parity §3.1).
     */
    fun clear(userId: UUID) {
        val dir = userDirectory(userId) ?: return clearAll()
        dir.deleteRecursively()
    }

    /** Deletes `shroud/messages` (`clearAll`, `:367-374`). */
    fun clearAll() {
        messagesDir.deleteRecursively()
    }

    // ---- Prune ----

    /**
     * Retention (`prune`, `:376-415`; messaging-core §22.4): the cutoff is 90 calendar days before
     * [now] in the phone's time zone (`Calendar.current`). Every thread but Notes keeps messages from
     * the cutoff on and every `pendingSync` one; a thread left empty goes. A conversation stays while
     * its peer has a thread, else while its last message (or its creation) is within the window.
     * Returns the pruned snapshot and the dropped message ids (for the cache clean-up).
     */
    fun prune(snapshot: Snapshot, now: Instant = clock.now()): Pair<Snapshot, List<UUID>> {
        val cutoff = cutoff(now)
        val dropped = ArrayList<UUID>()
        val threads = LinkedHashMap<UUID, List<StoredMessage>>()
        for ((peer, messages) in snapshot.threads) {
            if (peer == NOTES_PEER_ID) {
                threads[peer] = messages
                continue
            }
            val kept = keepRecent(messages, cutoff, dropped)
            if (kept.isNotEmpty()) threads[peer] = kept
        }
        val conversations = snapshot.conversations.filter { conversation ->
            if (threads.containsKey(conversation.peerId)) return@filter true
            val last = conversation.lastMessageAt ?: conversation.createdAt
            last >= cutoff
        }
        return snapshot.copy(threads = threads, conversations = conversations) to dropped
    }

    /**
     * One thread's retention (`persistThread`'s own prune, `MessagingLocalRepository.swift:239-259`):
     * Notes keep everything; others keep recent and pending rows. Dropped ids go into [dropped].
     */
    fun pruneThread(peerId: UUID, messages: List<StoredMessage>, dropped: MutableList<UUID>, now: Instant = clock.now()): List<StoredMessage> =
        if (peerId == NOTES_PEER_ID) messages else keepRecent(messages, cutoff(now), dropped)

    /** `Calendar.current.date(byAdding: .day, value: -90, to: now)`. */
    fun cutoff(now: Instant): Instant = ZonedDateTime.ofInstant(now, zone()).minusDays(RETENTION_DAYS.toLong()).toInstant()

    private fun keepRecent(messages: List<StoredMessage>, cutoff: Instant, dropped: MutableList<UUID>): List<StoredMessage> =
        messages.filter { message ->
            when {
                message.createdAt >= cutoff -> true
                message.pendingSync == true -> true
                else -> {
                    dropped += message.id
                    false
                }
            }
        }

    // ---- Sealed JSON I/O (`encodeSealed` / `decodeSealed`, `:447-479`) ----

    private fun <T> encode(at: Location, serializer: KSerializer<T>, value: T): SealedWrite? {
        val plain = LocalStoreJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        try {
            val sealed = keys.seal(LocalHistoryCrypto.Context.MessagesSnapshot, plain, at.aad) ?: return null
            return SealedWrite(at, sealed)
        } finally {
            plain.fill(0)
        }
    }

    private fun <T> decode(at: Location, serializer: KSerializer<T>): T? {
        val blob = LocalFiles.read(at.file) ?: return null
        val plain = keys.open(LocalHistoryCrypto.Context.MessagesSnapshot, blob, at.aad) ?: return null
        return try {
            LocalStoreJson.decodeFromString(serializer, String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        } finally {
            plain.fill(0)
        }
    }

    companion object {
        /** `retentionDays` (`:14`). */
        const val RETENTION_DAYS = 90

        const val MESSAGES = "shroud/messages"
        const val THREADS = "threads"
        const val ROSTER = "roster.sealed"
        const val REACTIONS = "reactions.sealed"
        const val ANNOTATIONS = "annotations.sealed"
        const val SEALED = ".sealed"
    }
}
