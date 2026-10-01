package de.corespace.shroud.core.messaging.local

import de.corespace.shroud.core.crypto.Entropy
import de.corespace.shroud.core.crypto.SystemEntropy
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.media.LocalMediaStore
import de.corespace.shroud.core.messaging.HydratedMessages
import de.corespace.shroud.core.messaging.MessagingSnapshot
import de.corespace.shroud.core.messaging.MessagingStore
import de.corespace.shroud.core.messaging.RosterSnapshot
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.hasLargeLinkImage
import de.corespace.shroud.core.net.wire.MediaMessagePayload
import de.corespace.shroud.core.net.wire.MessageTextPayload
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The sealed local message store behind [MessagingStore] (iOS `MessagingLocalRepository`,
 * `ios/shroud/Services/Messaging/MessagingLocalRepository.swift:4-405`; messaging-core §22.3,
 * §23.3; plan §1.5, §1.7.7): the roster and thread files ([LocalMessageStore]), the plaintext cache
 * ([LocalPlaintextCache]), the media cache's presence and purges ([LocalMediaStore], W2-MEDIA-STORE)
 * and the Android annotation index (messaging-core D5).
 *
 * **Key.** iOS hands the repository its history key (`setHistoryKey`, `:40-46`); here every access
 * goes through [SealedLocalState] (W1-KEYS), which `CryptoController` locks and unlocks with the
 * chats. A lock or an unlock forgets the record of what was written and the plaintext kept in
 * memory, as iOS's `setHistoryKey` / `lockSensitiveMemory` do (`:40-53`).
 *
 * **Threads.** Blocking; call off the main thread. Thread-safe: every operation on
 * `shroud/messages/` runs under one I/O lock, file writes are queued on one serial writer with
 * per-file coalescing ([LocalWriteQueue], messaging-core §23.3) and drained by [flush],
 * [lockSensitiveMemory] and before every read of those files. Plaintext saves are synchronous
 * (a one-shot ratchet key is gone once the decrypt returns).
 *
 * **Written-state skip** (`:24-38, 302-360`): the repository remembers, per account, what each file
 * holds (as it last wrote or read it) and skips a save that would write the same content; a lock,
 * an unlock or a [clear] forgets it, and unknown means "write".
 *
 * **Android additions.** The annotation index (`annotations.sealed`, D5): [noteAnnotation] records
 * which transcript annotations point at a voice message, and [removeCaches] purges them with it (and
 * drops a purged annotation from its target). Purges that cannot be applied yet (chats locked, so
 * the keyed file names are unknown) wait in memory for the next unlocked call. Plaintext is re-cached
 * only for the messages a prune keeps (iOS re-caches pruned ones too and then, in `persistThread`,
 * leaves them behind, `:256-261`).
 *
 * Never logs ids, names, keys or content.
 *
 * @param root `Context.noBackupFilesDir` (tests: a temp directory).
 * @param localMedia the SHRM1 media cache (W2-MEDIA-STORE), read on every call; null until wired.
 * @param zone the time zone of the 90-day retention (`Calendar.current`).
 * @param writerScope runs the queued file writes (default: one IO thread).
 * @param flushDispatcher where [flush] drains the queue.
 */
class MessagingLocalRepository(
    root: File,
    private val state: SealedLocalState,
    private val storageSeal: StorageSeal,
    clock: AppClock,
    private val localMedia: () -> LocalMediaStore? = { null },
    zone: () -> ZoneId = ZoneId::systemDefault,
    writerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
    private val flushDispatcher: CoroutineDispatcher = Dispatchers.IO,
    entropy: Entropy = SystemEntropy,
) : MessagingStore {
    private val keys = LocalStoreKeys(state, entropy)
    internal val messageStore = LocalMessageStore(root, keys, clock, storageSeal, zone)
    internal val plaintextCache = LocalPlaintextCache(root, keys, storageSeal)

    private val io = ReentrantLock()
    internal val writer = LocalWriteQueue(io, writerScope)

    /** What one account's files hold (`Written`, `:31-36`). [peers] null = unknown. */
    private class Written(val userId: UUID) {
        var roster: Roster? = null
        val threads = HashMap<UUID, List<StoredMessage>>()
        var peers: Set<UUID>? = null
    }

    // All guarded by `io`.
    private var written: Written? = null
    private var boundUser: UUID? = null
    private var annotations: HashMap<UUID, LinkedHashSet<UUID>>? = null
    private val pendingNotes = ArrayList<Pair<UUID, UUID>>()
    private val deferredRemovals = LinkedHashSet<UUID>()
    private var seenEpoch = 0L

    /** Bumped by every chat lock and unlock; the I/O sections compare it before they use memory state. */
    private val keyEpoch = AtomicLong()

    @Suppress("unused") // Kept for the life of the process, like the repository.
    private val keyListener = state.addListener(object : SealedLocalState.Listener {
        override fun onUnlock(historyKey: ByteArray) {
            keyEpoch.incrementAndGet()
            plaintextCache.clearMemory()
        }

        override fun onLock() {
            keyEpoch.incrementAndGet()
            plaintextCache.clearMemory()
        }
    })

    // ---- Plaintext cache (`:55-75`) ----

    override fun plaintext(messageId: UUID): ByteArray? = plaintextCache.data(messageId)

    override fun savePlaintext(messageId: UUID, bytes: ByteArray) = plaintextCache.save(messageId, bytes)

    // ---- Hydrate (`:110-186`) ----

    /**
     * Roster + threads from disk; locked → empty, with the Notes key present (`:112-117`). Prunes
     * (and re-saves when anything was dropped), rebuilds each message with its media presence and
     * envelope preview, scrubs tombstones that still carry content (older builds merged a missed
     * delete in with the content, messaging-core §10.6) and rewrites their threads, re-caches the
     * text plaintext, and records what was read as what the files hold.
     */
    override fun hydrate(userId: UUID): HydratedMessages = io.withLock { hydrateLocked(userId) }

    private fun hydrateLocked(userId: UUID): HydratedMessages {
        syncEpoch()
        writer.drain()
        if (!keys.isUnlocked) return emptyHydrated()
        bind(userId)
        val loaded = messageStore.load(userId) ?: return emptyHydrated()

        val (snapshot, dropped) = messageStore.prune(loaded)
        if (dropped.isNotEmpty()) {
            removeCachesLocked(dropped)
            messageStore.save(snapshot, userId)
        }

        val threads = LinkedHashMap<UUID, List<ChatMessage>>()
        threads[NOTES_PEER_ID] = emptyList()
        val rowsRead = LinkedHashMap(snapshot.threads)
        val scrubbed = ArrayList<UUID>()
        for ((peer, rows) in snapshot.threads) {
            val scrubbedBefore = scrubbed.size
            val messages = rows.map { row ->
                // Offline open: the envelope preview and size come back without a download.
                var message = attachEnvelopePreview(row.toChatMessage(::hasFullMedia))
                if (message.deleted) {
                    val tombstone = LocalTombstones.tombstone(message)
                    if (message != tombstone) {
                        scrubbed += message.id
                        message = tombstone
                    }
                }
                message
            }
            if (scrubbed.size > scrubbedBefore) {
                val rewritten = messages.map(StoredMessage::from)
                messageStore.saveThread(peer, rewritten, userId)
                rowsRead[peer] = rewritten
            }
            threads[peer] = messages
            recachePlaintext(messages)
        }
        if (scrubbed.isNotEmpty()) removeCachesLocked(scrubbed)

        // What was just read (or re-saved after the prune or the scrub) is what the files hold. The
        // peer set stays unknown: a thread file that did not decode is still on disk, and the first
        // full save should clear it out (`:165-176`). Android fix: without a roster file (an empty
        // install) the roster stays unknown too. iOS records an empty roster there, so a save with
        // nothing in the roster (Notes only, no contacts or chats yet) never writes one, and the
        // next load — which reads threads only after a roster (`LocalMessageStore.swift:237-240`) —
        // ignores every thread file.
        written = Written(userId).also {
            it.roster = if (snapshot.rosterOnDisk) snapshot.roster().content() else null
            it.threads.putAll(rowsRead)
        }

        val unread = LinkedHashMap<UUID, Int>()
        for ((key, count) in snapshot.unreadByPeer) Ids.parse(key)?.let { unread[it] = count }
        return HydratedMessages(
            roster = RosterSnapshot(
                conversations = snapshot.conversations.map(StoredConversation::toCached),
                contacts = snapshot.contacts.map(CachedContact::toDto),
                incomingRequests = snapshot.incomingRequests.map(CachedContactRequest::toDto),
                unreadByPeer = unread,
            ),
            threads = threads,
        )
    }

    // ---- Persist (`:188-277`) ----

    /**
     * Writes the roster and every thread (`persist`, `:188-224`): prunes the 90-day window, purges
     * the dropped messages' caches, skips files whose content did not change and removes the thread
     * files of peers no longer present (only when the peer set changed). Returns the dropped ids; the
     * controller removes them from memory (MC:4613-4630). Locked or wiping → nothing, empty set.
     */
    override fun persist(userId: UUID, snapshot: MessagingSnapshot): Set<UUID> {
        if (storageSeal.isSealed) return emptySet()
        val dropped = io.withLock {
            syncEpoch()
            if (!keys.isUnlocked) return emptySet()
            bind(userId)
            val stored = LocalMessageStore.Snapshot(
                conversations = snapshot.roster.conversations.map(StoredConversation::from),
                contacts = snapshot.roster.contacts.map(CachedContact::from),
                incomingRequests = snapshot.roster.incomingRequests.map(CachedContactRequest::from),
                threads = snapshot.threads.mapValues { (_, messages) -> messages.map(StoredMessage::from) },
                unreadByPeer = unreadOnDisk(snapshot.roster.unreadByPeer),
            )
            val (pruned, prunedIds) = messageStore.prune(stored)
            if (prunedIds.isNotEmpty()) removeCachesLocked(prunedIds)
            writeSnapshot(pruned, userId)
            prunedIds.toHashSet()
        }
        for (messages in snapshot.threads.values) recachePlaintext(messages, skip = dropped)
        return dropped
    }

    /**
     * Writes one thread and the roster (`persistThread`, `:226-277`): this peer alone is pruned
     * (Notes exempt, pending kept) and the roster is always offered, so list previews and unread
     * stay current. Returns the dropped ids — Android addition: iOS prunes them from disk only.
     */
    override fun persistThread(userId: UUID, storePeer: UUID, messages: List<ChatMessage>, roster: RosterSnapshot): Set<UUID> {
        if (storageSeal.isSealed) return emptySet()
        val dropped = io.withLock {
            syncEpoch()
            if (!keys.isUnlocked) return emptySet()
            bind(userId)
            val prunedIds = ArrayList<UUID>()
            val kept = messageStore.pruneThread(storePeer, messages.map(StoredMessage::from), prunedIds)
            if (prunedIds.isNotEmpty()) removeCachesLocked(prunedIds)
            writeThread(storePeer, kept, userId)
            writeRoster(rosterOnDisk(roster), userId)
            prunedIds.toHashSet()
        }
        recachePlaintext(messages, skip = dropped)
        return dropped
    }

    /** Waits for every queued write (before a lock drops the key, plan §1.4). */
    override suspend fun flush() = withContext(flushDispatcher) { writer.drain() }

    // ---- Caches (`:105-108`) and the annotation index (D5) ----

    /**
     * Drops the plaintext and local media of [messageIds] (`removeCaches`, `:105-108`; purge,
     * messaging-core §14.5) — and, Android (D5), the transcript annotations that point at any of
     * them; an id that is itself an annotation leaves the index. While chats are locked the keyed
     * names are unknown: the purge waits in memory and runs on the next unlocked call.
     */
    override fun removeCaches(messageIds: Collection<UUID>) {
        if (messageIds.isEmpty()) return
        io.withLock {
            syncEpoch()
            removeCachesLocked(messageIds)
        }
    }

    /**
     * Records that [annotationId] (a shared transcript) points at [targetId] (a voice message),
     * whenever an annotation is decoded or sent (messaging-core §14.6, D5).
     */
    override fun noteAnnotation(targetId: UUID, annotationId: UUID) {
        io.withLock {
            syncEpoch()
            val user = boundUser
            val index = user?.let(::annotationIndex)
            if (user == null || index == null) {
                if (pendingNotes.none { it.first == targetId && it.second == annotationId }) pendingNotes += targetId to annotationId
                return
            }
            if (index.getOrPut(targetId) { LinkedHashSet() }.add(annotationId)) saveAnnotations(user, index)
        }
    }

    /** The annotations known to point at [targetId]. */
    override fun annotationsFor(targetId: UUID): Set<UUID> = io.withLock {
        syncEpoch()
        val result = LinkedHashSet<UUID>()
        boundUser?.let(::annotationIndex)?.get(targetId)?.let(result::addAll)
        pendingNotes.filter { it.first == targetId }.forEach { result += it.second }
        result
    }

    // ---- Reaction cursors (`:362-384`) ----

    /** Peer → highest reaction `seq` applied; null while locked or when the file does not open, empty when there is none. */
    override fun reactionCursors(userId: UUID): Map<UUID, Long>? {
        val stored = io.withLock {
            syncEpoch()
            writer.drain()
            messageStore.loadReactionCursors(userId)
        } ?: return null
        val cursors = LinkedHashMap<UUID, Long>()
        for ((peer, seq) in stored.byPeer) Ids.parse(peer)?.let { cursors[it] = seq }
        return cursors
    }

    override fun saveReactionCursors(userId: UUID, cursors: Map<UUID, Long>) {
        if (storageSeal.isSealed) return
        io.withLock {
            syncEpoch()
            val file = ReactionCursorsFile(byPeer = cursors.entries.associate { Ids.wire(it.key) to it.value })
            val sealed = messageStore.sealReactionCursors(file, userId) ?: return
            writer.enqueue(sealed.location.aad) { messageStore.write(sealed) }
        }
    }

    // ---- Lock and clear (`:48-53`, `:386-404`) ----

    /**
     * Flushes the queued writes, then drops what this unlock kept in memory: the plaintext L1, the
     * written-state record and the annotation index (`lockSensitiveMemory`, `:48-53`). The key
     * itself is [SealedLocalState]'s, locked by `CryptoController`.
     */
    override fun lockSensitiveMemory() {
        io.withLock {
            writer.drain()
            forgetMemoryLocked()
        }
        plaintextCache.clearMemory()
    }

    /**
     * Deletes one account's store — or every account's when [userId] is null — and the whole
     * plaintext and media caches (`clear(userID:)`, `:394-404`). Queued writes are dropped first.
     * The account directory's name needs the key; while chats are locked every account's store goes.
     */
    override fun clear(userId: UUID?) {
        io.withLock {
            writer.clear()
            forgetMemoryLocked()
            pendingNotes.clear()
            deferredRemovals.clear()
            if (userId != null) messageStore.clear(userId) else messageStore.clearAll()
        }
        plaintextCache.clearAll()
        localMedia()?.clearAll()
    }

    // ---- Internals ----

    private fun emptyHydrated() = HydratedMessages(
        roster = RosterSnapshot(emptyList(), emptyList(), emptyList(), emptyMap()),
        threads = mapOf(NOTES_PEER_ID to emptyList()),
    )

    /** A lock or unlock happened since the last I/O section: forget what that key's unlock knew. */
    private fun syncEpoch() {
        val epoch = keyEpoch.get()
        if (epoch != seenEpoch) {
            seenEpoch = epoch
            forgetMemoryLocked()
        }
    }

    private fun forgetMemoryLocked() {
        written = null
        boundUser = null
        annotations = null
    }

    /** The account the annotation index belongs to; notes and purges that waited for it are applied. */
    private fun bind(userId: UUID) {
        if (boundUser != userId) {
            boundUser = userId
            annotations = null
        }
        val index = annotationIndex(userId) ?: return
        if (pendingNotes.isNotEmpty()) {
            for ((target, annotation) in pendingNotes) index.getOrPut(target) { LinkedHashSet() }.add(annotation)
            pendingNotes.clear()
            saveAnnotations(userId, index)
        }
        if (deferredRemovals.isNotEmpty()) {
            val ids = deferredRemovals.toList()
            deferredRemovals.clear()
            removeCachesLocked(ids)
        }
    }

    /** The bound account's index, read once per unlock (after the queued writes); null while locked. */
    private fun annotationIndex(userId: UUID): HashMap<UUID, LinkedHashSet<UUID>>? {
        annotations?.let { return it }
        writer.drain()
        val stored = messageStore.loadAnnotations(userId) ?: return null
        val index = HashMap<UUID, LinkedHashSet<UUID>>()
        for ((target, ids) in stored.byTarget) {
            val targetId = Ids.parse(target) ?: continue
            val set = ids.mapNotNullTo(LinkedHashSet()) { Ids.parse(it) }
            if (set.isNotEmpty()) index[targetId] = set
        }
        annotations = index
        return index
    }

    private fun saveAnnotations(userId: UUID, index: Map<UUID, Set<UUID>>) {
        if (storageSeal.isSealed) return
        val file = AnnotationIndexFile(byTarget = index.entries.associate { (target, ids) -> Ids.wire(target) to ids.map(Ids::wire) })
        val sealed = messageStore.sealAnnotations(file, userId) ?: return
        writer.enqueue(sealed.location.aad) { messageStore.write(sealed) }
    }

    private fun removeCachesLocked(messageIds: Collection<UUID>) {
        val user = boundUser
        val index = user?.let(::annotationIndex)
        val targets = LinkedHashSet(messageIds)
        if (index != null) {
            var changed = false
            for (id in messageIds) index.remove(id)?.let {
                targets += it
                changed = true
            }
            val purged = messageIds.toHashSet()
            val entries = index.entries.iterator()
            while (entries.hasNext()) {
                val entry = entries.next()
                if (entry.value.removeAll(purged)) changed = true
                if (entry.value.isEmpty()) entries.remove()
            }
            if (changed && user != null) saveAnnotations(user, index)
        }
        val waiting = pendingNotes.filter { it.first in targets || it.second in targets }
        waiting.forEach { targets += it.second }
        pendingNotes.removeAll(waiting.toSet())

        val unnamed = plaintextCache.remove(targets)
        try {
            localMedia()?.remove(targets)
        } catch (_: Exception) {
            // The media cache's own failure; its files are wiped with the store.
        }
        // Without the index the annotations of these ids are unknown yet: purge again once it is here.
        if (index == null) deferredRemovals += messageIds else deferredRemovals += unnamed
    }

    private fun hasFullMedia(messageId: UUID): Boolean = try {
        localMedia()?.has(messageId) == true
    } catch (_: Exception) {
        false
    }

    /**
     * Fills the preview and byte count from the cached media payload (`attachEnvelopePreview`,
     * `:87-103`) for photos, videos and large link images; a video keeps the preview as its poster.
     */
    private fun attachEnvelopePreview(message: ChatMessage): ChatMessage {
        val carries = message.kind == ChatMessageKind.Image || message.kind == ChatMessageKind.Video || message.hasLargeLinkImage
        if (!carries) return message
        val plain = plaintextCache.data(message.id) ?: return message
        val payload = try {
            MediaMessagePayload.parse(plain)
        } finally {
            plain.fill(0)
        } ?: return message
        var result = message
        if (result.previewJpeg == null) payload.previewJpeg?.let { result = result.copy(previewJpeg = Bytes.adopt(it)) }
        if (result.mediaByteCount == null && payload.s != null) result = result.copy(mediaByteCount = payload.s)
        if (result.kind == ChatMessageKind.Video && result.posterJpeg == null && result.previewJpeg != null) {
            result = result.copy(posterJpeg = result.previewJpeg)
        }
        return result
    }

    /**
     * Keeps each text message's cached plaintext equal to the wire it decodes from, quote and
     * preview included (`recachePlaintext`, `:281-300`). A link message whose picture is a media
     * blob keeps its media payload (it holds the blob key); failed decrypts and tombstones are left
     * alone. An entry that already holds these bytes is not rewritten.
     */
    private fun recachePlaintext(messages: List<ChatMessage>, skip: Set<UUID> = emptySet()) {
        for (message in messages) {
            if (message.deleted || message.kind != ChatMessageKind.Text || message.mediaObjectId != null) continue
            if (LocalTombstones.isFailedDecryptText(message.text) || message.id in skip) continue
            val wire = MessageTextPayload.wire(message.text, message.replyTo, message.linkPreview)
            if (plaintextCache.text(message.id) != wire) plaintextCache.save(message.id, wire)
        }
    }

    private fun writtenFor(userId: UUID): Written =
        written?.takeIf { it.userId == userId } ?: Written(userId).also { written = it }

    /** `writeRoster` (`:314-321`). */
    private fun writeRoster(roster: Roster, userId: UUID) {
        val state = writtenFor(userId)
        val content = roster.content()
        if (state.roster == content) return
        val sealed = messageStore.sealRoster(roster, userId) ?: return
        writer.enqueue(sealed.location.aad) { messageStore.write(sealed) }
        state.roster = content
    }

    /** `writeThread` (`:323-336`). */
    private fun writeThread(peerId: UUID, rows: List<StoredMessage>, userId: UUID) {
        val state = writtenFor(userId)
        if (state.threads[peerId] == rows) return
        val sealed = messageStore.sealThread(peerId, rows, userId) ?: return
        writer.enqueue(sealed.location.aad) { messageStore.write(sealed) }
        state.threads[peerId] = rows
        state.peers?.let { state.peers = it + peerId }
    }

    /** `writeSnapshot` (`:338-360`): one file at a time, skipping files that would not change. */
    private fun writeSnapshot(snapshot: LocalMessageStore.Snapshot, userId: UUID) {
        writeRoster(snapshot.roster(), userId)
        for ((peer, rows) in snapshot.threads) writeThread(peer, rows, userId)
        val wanted = snapshot.threads.keys.toSet()
        val state = writtenFor(userId)
        if (state.peers == wanted) return
        val cleanup = messageStore.threadCleanup(wanted, userId) ?: return
        val dir = messageStore.userDirectory(userId) ?: return
        writer.enqueue(CLEANUP_KEY + dir.name) { cleanup() }
        state.peers = wanted
        state.threads.keys.retainAll(wanted)
    }

    private fun rosterOnDisk(roster: RosterSnapshot) = Roster(
        conversations = roster.conversations.map(StoredConversation::from),
        contacts = roster.contacts.map(CachedContact::from),
        incomingRequests = roster.incomingRequests.map(CachedContactRequest::from),
        unreadByPeer = unreadOnDisk(roster.unreadByPeer),
    )

    /** Only counts above zero, keyed by the lower-case peer id (`:212-216`). */
    private fun unreadOnDisk(unread: Map<UUID, Int>): Map<String, Int> =
        unread.entries.filter { it.value > 0 }.associate { Ids.wire(it.key) to it.value }

    private companion object {
        /** Queue key of an account's thread clean-up (one per account; a newer one replaces it). */
        const val CLEANUP_KEY = "threads-cleanup:"
    }
}
