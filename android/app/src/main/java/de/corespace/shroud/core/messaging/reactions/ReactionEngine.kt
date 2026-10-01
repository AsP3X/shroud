package de.corespace.shroud.core.messaging.reactions

import android.content.SharedPreferences
import androidx.core.content.edit
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.MessageCrypto
import de.corespace.shroud.core.crypto.OpenAs
import de.corespace.shroud.core.messaging.ReactionsEngine
import de.corespace.shroud.core.messaging.SendDependencies
import de.corespace.shroud.core.messaging.SendHost
import de.corespace.shroud.core.messaging.ThreadState
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.MessageReaction
import de.corespace.shroud.core.model.ReactionFailure
import de.corespace.shroud.core.model.ReceiptStatus
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.ReactionDto
import de.corespace.shroud.core.net.ReactionWriteResult
import de.corespace.shroud.core.net.wire.MessageReactionPayload
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.realtime.RealtimeEvent
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Reactions (messaging-core §19.1, §19.3–§19.8; the [ReactionsEngine] seam of plan §1.7.7), ported
 * from `ios/shroud/Services/Messaging/MessagingController.swift` (MC below, its `// MARK: - Reactions`
 * extension, MC:5060-5956) with web `reactions.ts` as the second reference.
 *
 * A reaction is not a message: the server keeps one sealed record per (message, user) — the
 * person's whole set — with a per-chat `seq` that is last-write-wins per user; removals stay as
 * entries without emoji. The pure merge rules are [ReactionMerge] (W1-WIRE); this engine:
 * - **writes ours** ([set], [toggle]): shown at once as a pending entry, saved in the background,
 *   one request per message at a time; taps meanwhile collapse into one follow-up with the last
 *   choice. A `409` from our other device rebases what we changed onto its set (≤ 3 rounds); a
 *   failure puts back what the server holds and emits a [ReactionFailure];
 * - **reads theirs** from history pages ([openPage], [reconcilePage], [applyPage]), the socket
 *   ([apply]) and the catch-up feed ([catchUp]), behind per-chat cursors kept in the sealed store;
 * - keeps the **heart badge** of unseen reactions ([hasUnseen], [onChatShown], [markSeen], [applyLocalSeen]).
 *
 * Records are tagged v2 identity envelopes (peer + self box, never the ratchet): a reaction is
 * overwritten in place, so ratchet steps would be lost, and every device must open it at any time.
 *
 * Main-confined. Work runs in the engine's own scope, a child of [SendDependencies.scope]; [reset]
 * cancels it (sign-out, wipe).
 */
class ReactionEngine(
    private val state: ThreadState,
    private val host: () -> SendHost,
    private val deps: SendDependencies,
    private val prefs: SharedPreferences,
    private val storageSeal: StorageSeal,
    private val timing: Timing = Timing(),
) : ReactionsEngine {
    /** Debounces of the engine (MC:5859, 5871). */
    data class Timing(val persistDelayMs: Long = 600, val refreshSoonDelayMs: Long = 700)

    private val job = SupervisorJob(deps.scope.coroutineContext[Job])
    private val scope = CoroutineScope(deps.scope.coroutineContext + job)

    /** Our latest wanted set per message while its save is in flight (MC:5069-5073). */
    private class Intent(val storePeer: UUID, val emojis: List<String>)

    /**
     * Our record as the server last confirmed it ([entry], null: none) and the chat's catch-up cursor
     * when the first tap went out (MC:5075-5083).
     */
    private class Rollback(var entry: MessageReaction?, val cursor: Long?)

    private val limitFlow = MutableStateFlow(prefs.getInt(LIMIT_KEY, DEFAULT_LIMIT))
    private val revisionFlow = MutableStateFlow<Map<UUID, Int>>(emptyMap())
    private val failureFlow = MutableSharedFlow<ReactionFailure>(extraBufferCapacity = 16)

    private val intents = HashMap<UUID, Intent>()
    private val sendJobs = HashMap<UUID, Job>()
    private val rollback = HashMap<UUID, Rollback>()
    private val persistJobs = HashMap<UUID, Job>()

    /** Catch-up cursors (store peer → highest `seq` applied), read from the sealed store once (MC:163-164). */
    private var cursorCache: Map<UUID, Long>? = null

    /** Highest `seq` this device marked seen per chat (MC:174-176). */
    private val seenLocally = HashMap<UUID, Long>()
    private var refreshSoonJob: Job? = null

    /** Chats with a catch-up running → the lowest page snapshot published meanwhile (MC:181-183). */
    private val catchUpFloors = HashMap<UUID, Long>()

    /** The newest history-page snapshot per chat, for [catchUp] (Android: the seam passes no snapshot). */
    private val pageSnapshots = HashMap<UUID, Long>()

    /** Most emoji one person may leave on one message: `GET /config`, remembered in `shroud.messaging`, 5 until the server said (MC:168-171). */
    override val limit: StateFlow<Int> = limitFlow.asStateFlow()

    /**
     * Per chat (store peer), bumped when a reaction changes — a tap, the other side, our other device —
     * and not when history loads, so the thread animates chips and not rows scrolling in
     * (`reactionRevisions`, MC:178-180, 5331-5337).
     */
    override val revisions: StateFlow<Map<UUID, Int>> = revisionFlow.asStateFlow()

    /** A reaction of ours that could not be saved; the chip went back. The chat shows it as a toast (MC:165-167). */
    override val failures: SharedFlow<ReactionFailure> = failureFlow.asSharedFlow()

    // ---- reading ours ----

    /**
     * A message with a server id that can carry reactions: not deleted, not still sending, not in
     * Notes, where much exists only on this device (`canReact`, MC:5087-5095).
     */
    override fun canReact(message: ChatMessage): Boolean =
        !message.deleted &&
            !state.isNotes(message.peerUserId) &&
            message.kind != ChatMessageKind.Todo &&
            !message.pendingSync &&
            message.receipt != ReceiptStatus.Sending &&
            message.receipt != ReceiptStatus.Failed &&
            message.sendError == null

    /** Our emoji on [message], oldest first, unconfirmed changes included (`myReactions`, MC:5098-5101). */
    override fun myReactions(message: ChatMessage): List<String> {
        val me = state.myUserId ?: return emptyList()
        return ReactionMerge.emojis(me, message.reactions)
    }

    // ---- writing ours ----

    /** Takes [emoji] back when it is ours, else adds it; past the limit our oldest goes (`toggleReaction`, MC:5105-5109). */
    override fun toggle(emoji: String, messageId: UUID, storePeer: UUID) {
        val message = state.messages(storePeer)?.firstOrNull { it.id == messageId } ?: return
        set(ReactionMerge.toggled(emoji, myReactions(message), limitFlow.value), messageId, storePeer)
    }

    /**
     * Shows our set at once and saves it in the background (`setMyReactions`, MC:5128-5154): one
     * request per message at a time; taps while it runs only update what we want.
     */
    override fun set(emojis: List<String>, messageId: UUID, storePeer: UUID) {
        val me = state.myUserId ?: return
        val message = state.messages(storePeer)?.firstOrNull { it.id == messageId } ?: return
        if (!canReact(message) || !emojis.all(MessageReactionPayload::isSingleEmoji) || emojis.toSet().size != emojis.size) return
        val current = message.reactions.firstOrNull { it.userId == me }
        if ((current?.emojis ?: emptyList()) == emojis) return

        if (sendJobs[messageId] == null) rollback[messageId] = Rollback(current, cursors()[storePeer])
        val optimistic = MessageReaction(me, emojis, current?.seq ?: 0, pending = true)
        state.update(messageId) { it.copy(reactions = ReactionMerge.replacing(me, optimistic, it.reactions)) }
        noteChanged(storePeer)

        intents[messageId] = Intent(storePeer, emojis)
        if (sendJobs[messageId] == null) {
            val drain = scope.launch(start = CoroutineStart.LAZY) { drain(messageId) }
            sendJobs[messageId] = drain
            drain.start()
        }
    }

    /** `drainReactionIntents` (MC:5159-5249). */
    private suspend fun drain(messageId: UUID) {
        val self = coroutineContext[Job]
        var storePeer: UUID? = null
        var rebases = 0
        // The set of a save whose answer never came: it may have landed all the same (MC:5162-5163).
        var unanswered: List<String>? = null
        while (true) {
            val intent = intents.remove(messageId) ?: break
            storePeer = intent.storePeer
            val me = state.myUserId ?: break
            val base = rollback[messageId]?.entry
            try {
                when (val result = sendReaction(intent, base, messageId)) {
                    is ReactionWriteResult.Saved -> {
                        unanswered = null
                        rebases = 0
                        // 204 on a removal: the server held none, which is what we wanted (MC:5176-5178).
                        val confirmed = result.reaction?.let { MessageReaction(me, intent.emojis, it.seq) }
                            ?: base?.let { MessageReaction(me, emptyList(), it.seq) }
                        rollback[messageId]?.entry = confirmed
                        if (intents[messageId] == null) replaceMine(confirmed, messageId, intent.storePeer, me)
                    }
                    is ReactionWriteResult.ChangedElsewhere -> {
                        val currentDto = result.current
                        // Only our own record on this very message can be that answer (MC:5184-5187).
                        if (currentDto.userId != me || currentDto.messageId != messageId) throw ApiError.Decoding("foreign reaction record")
                        // Our other device wrote first: what we changed since `base` goes on top of its set (MC:5188-5218).
                        val current = openOwn(currentDto, me)
                        rollback[messageId]?.entry = current
                        val landed = unanswered == current.emojis
                        unanswered = null
                        val wanted = intents[messageId]?.emojis ?: intent.emojis
                        val merged = ReactionMerge.rebased(
                            mine = wanted,
                            base = if (landed) current.emojis else base?.emojis ?: emptyList(),
                            theirs = current.emojis,
                            limit = limitFlow.value,
                        )
                        rebases += 1
                        if (merged == current.emojis || rebases > MAX_REBASES) {
                            intents.remove(messageId)
                            replaceMine(current, messageId, intent.storePeer, me)
                            if (merged != current.emojis) fail(messageId, COULD_NOT_SAVE)
                        } else {
                            intents[messageId] = Intent(intent.storePeer, merged)
                            replaceMine(MessageReaction(me, merged, current.seq, pending = true), messageId, intent.storePeer, me)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Signed out meanwhile (`cancelReactionWork`): nothing to put back, nobody to tell (MC:5221-5222).
                throw e
            } catch (_: Exception) {
                unanswered = intent.emojis
                // A newer choice is queued; it tries again with its own request (MC:5224-5225).
                if (intents[messageId] != null) continue
                replaceMine(rollback[messageId]?.entry, messageId, intent.storePeer, me)
                // A change from our other device ignored while our tap was pending comes back via catch-up (MC:5232-5236).
                val cursor = rollback[messageId]?.cursor
                val now = cursors()[intent.storePeer]
                if (cursor != null && now != null && cursor < now) saveCursor(cursor, intent.storePeer)
                fail(messageId, if (deps.isOnline()) COULD_NOT_SAVE else OFFLINE_NOT_SAVED)
            }
        }
        if (sendJobs[messageId] === self) sendJobs.remove(messageId)
        rollback.remove(messageId)
        storePeer?.let(::schedulePersist)
    }

    /**
     * Writes [intent] built on [base], our record as last confirmed (`sendReaction`, MC:5252-5288). A
     * removal is "none": `base_seq` 0 matches a removal row whatever its seq; only an emoji the base
     * lacked is news for the message's author (`added`).
     */
    private suspend fun sendReaction(intent: Intent, base: MessageReaction?, messageId: UUID): ReactionWriteResult {
        val token = state.session?.token
        if (!deps.isOnline() || token == null || !deps.keyring.isUnlocked) throw ApiError.Transport("offline")
        val baseSeq = base?.takeIf { it.isLive }?.seq ?: 0
        if (intent.emojis.isEmpty()) return deps.api.deleteReaction(token, messageId, baseSeq)
        val plaintext = MessageReactionPayload.make(intent.emojis, messageId).encoded()
        val peerPublic = if (state.isNotes(intent.storePeer)) {
            deps.ourPublicKey() ?: throw CryptoError.Locked
        } else {
            deps.peerIdentities().publicKeyForSending(state.apiPeer(intent.storePeer))
        }
        // v2 on purpose: overwritten in place, opened by every device at any time (MC:5271-5278).
        val sealed = deps.sealIdentityOnly(plaintext, peerPublic)
        val baseEmojis = base?.emojis ?: emptyList()
        return deps.api.putReaction(token, messageId, sealed, baseSeq, added = intent.emojis.any { it !in baseEmojis })
    }

    /** Our own record as a `409` returned it; a removal, or one that does not open, is none (`openOwnReaction`, MC:5291-5298). */
    private suspend fun openOwn(dto: ReactionDto, me: UUID): MessageReaction {
        val none = MessageReaction(me, emptyList(), dto.seq)
        if (state.session == null || !deps.keyring.isUnlocked) return none
        return try {
            open(dto, emptyList(), me)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            none
        }
    }

    /**
     * Swaps our entry on a message for [entry] (null: none) unless the message became a tombstone
     * (`replaceMyReaction`, MC:5339-5349).
     */
    private fun replaceMine(entry: MessageReaction?, messageId: UUID, storePeer: UUID, me: UUID) {
        val message = state.messages(storePeer)?.firstOrNull { it.id == messageId } ?: return
        if (message.deleted) return
        val updated = ReactionMerge.replacing(me, entry, message.reactions)
        if (updated == message.reactions) return
        state.update(messageId) { if (it.deleted) it else it.copy(reactions = updated) }
        noteChanged(storePeer)
    }

    private fun fail(messageId: UUID, text: String) {
        failureFlow.tryEmit(ReactionFailure(UUID.randomUUID(), messageId, text))
    }

    /**
     * What goes to disk: our unconfirmed reaction swapped for the one the server last confirmed
     * (`settledReactions`, MC:5317-5327). Saved as if confirmed, a pending set would outlive a failed
     * save. `ThreadStore` applies it to every thread it persists, on main — W2-MSG-CORE's
     * `ReactionsEngine.settled` (contract change request CR-2).
     */
    fun settled(messages: List<ChatMessage>): List<ChatMessage> {
        if (rollback.isEmpty()) return messages
        val me = state.myUserId ?: return messages
        return messages.map { message ->
            val back = rollback[message.id] ?: return@map message
            if (message.reactions.none { it.userId == me && it.pending }) return@map message
            message.copy(reactions = ReactionMerge.replacing(me, back.entry, message.reactions))
        }
    }

    // ---- reading theirs ----

    /**
     * Opens one sealed record (`openReaction`, MC:5386-5426). A record we hold at the same `seq` is
     * reused instead of decrypted again — ours too: a non-pending entry of ours is the record at its
     * `seq`. One that does not open counts as no reaction. Throws only when the sender's key is out of
     * reach right now ([isTransient]): the caller must come back for it rather than record a removal
     * the cursor would then move past.
     */
    private suspend fun open(dto: ReactionDto, held: List<MessageReaction>, me: UUID): MessageReaction {
        val removed = MessageReaction(dto.userId, emptyList(), dto.seq)
        val ciphertext = dto.ciphertext ?: return removed
        held.firstOrNull { it.userId == dto.userId && it.seq == dto.seq && !it.pending }?.let { return it }
        val envelope = MessageCrypto.fromWire(ciphertext) ?: return removed
        val mine = dto.userId == me
        val senderPublic: ByteArray? = if (mine) {
            null
        } else {
            try {
                deps.peerIdentities().resolvePublicKey(dto.userId)
            } catch (e: Exception) {
                if (isTransient(e)) throw e
                return removed
            }
        }
        val plaintext = try {
            // Tagged v2 only: every build that writes reactions tags its boxes (MC:5412-5420).
            deps.openTagged(envelope, senderPublic, if (mine) OpenAs.Sender else OpenAs.Recipient)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return removed
        } ?: throw CryptoError.Locked
        return MessageReaction(dto.userId, MessageReactionPayload.parse(plaintext, dto.messageId) ?: emptyList(), dto.seq)
    }

    /** What [openPage] found: the opened entries per message the page lists, its snapshot, the lowest record left unopened. */
    class PageReactions internal constructor(
        val serverIds: Set<UUID>,
        val reactions: Map<UUID, List<MessageReaction>>,
        val snapshot: Long?,
        val unopened: Long?,
    )

    /**
     * Opens one history page's records (`fetchHistoryPage`, MC:1221-1246): for each message the page
     * shows (not deleted) only records of that very message from the two people of the chat, the
     * newest per person ([ReactionMerge.pageRecords]); records already held at the same `seq` are
     * reused. A record whose sender's key is out of reach is left out and its `seq` noted
     * ([PageReactions.unopened]), the shown entry of that person kept (`openReactions`, MC:5353-5376).
     *
     * @param serverIds every message id of the page (annotations and dropped rows included).
     * @param records the page's `MessageDto.reactions` per message, for the bubbles it shows.
     * @param snapshot the page's `reaction_seq`.
     */
    suspend fun openPage(storePeer: UUID, serverIds: Set<UUID>, records: Map<UUID, List<ReactionDto>>, snapshot: Long?): PageReactions {
        val me = state.myUserId
        if (me == null || state.session == null || !deps.keyring.isUnlocked) return PageReactions(serverIds, emptyMap(), snapshot, null)
        val reactors = setOf(me, state.apiPeer(storePeer))
        val held = state.messages(storePeer).orEmpty().associate { it.id to it.reactions }
        val opened = HashMap<UUID, List<MessageReaction>>()
        var unopened: Long? = null
        for ((messageId, list) in records) {
            if (list.isEmpty()) continue
            val heldHere = held[messageId].orEmpty()
            val result = ArrayList<MessageReaction>()
            for (dto in ReactionMerge.pageRecords(messageId, list, reactors)) {
                try {
                    result += open(dto, heldHere, me)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    unopened = minOf(unopened ?: dto.seq, dto.seq)
                    // Keep what we show until catch-up brings it: left out, the page would read as a take-back (MC:5368-5372).
                    heldHere.firstOrNull { it.userId == dto.userId && !it.pending }?.let { result += it }
                }
            }
            opened[messageId] = result
        }
        if (snapshot != null) pageSnapshots[storePeer] = maxOf(pageSnapshots[storePeer] ?: 0, snapshot)
        return PageReactions(serverIds, opened, snapshot, unopened)
    }

    /**
     * Reconciles a merged thread with a page's reactions and moves the catch-up cursor and floor
     * (`publishHistoryPage`, MC:1270-1302). Returns [merged] with the page's messages reconciled
     * ([ReactionMerge.reconcile]); the caller publishes it.
     */
    fun reconcilePage(storePeer: UUID, page: PageReactions, merged: List<ChatMessage>): List<ChatMessage> {
        val snapshot = page.snapshot ?: return merged
        val result = merged.map { message ->
            if (message.id !in page.serverIds || message.deleted) return@map message
            val reconciled = ReactionMerge.reconcile(message.reactions, page.reactions[message.id].orEmpty(), snapshot)
            if (reconciled != message.reactions) message.copy(reactions = reconciled) else message
        }
        lowerCatchUp(storePeer, snapshot)
        // A record the page left out (its sender's key out of reach) comes via catch-up (MC:1292-1301).
        page.unopened?.let { lowerCatchUp(storePeer, it - 1) }
        return result
    }

    /**
     * The [ReactionsEngine] seam's page hook (`publishHistoryPage`, MC:1270-1302): [reactions] are the
     * page's trusted records ([ReactionMerge.pageRecords]). Moves cursor and floor at once, then opens
     * the records and reconciles the messages they name into the thread as it is by then — the order
     * against a catch-up running meanwhile does not matter, every entry carries its `seq`.
     *
     * The page's messages that came back without a record are the pager's to reconcile with an empty
     * list (W2-MSG-CORE's `HistoryPager.publishHistoryPage` does); a pager that also wants this
     * engine's opened entries back can call [openPage] + [reconcilePage] itself.
     */
    override fun applyPage(storePeer: UUID, reactions: List<ReactionDto>, snapshotSeq: Long?) {
        if (snapshotSeq == null) return
        pageSnapshots[storePeer] = maxOf(pageSnapshots[storePeer] ?: 0, snapshotSeq)
        lowerCatchUp(storePeer, snapshotSeq)
        if (reactions.isEmpty()) return
        val records = reactions.groupBy { it.messageId }
        val generation = state.lockGeneration
        scope.launch {
            val page = openPage(storePeer, records.keys, records, snapshotSeq)
            if (state.lockGeneration != generation || state.messages(storePeer) == null) return@launch
            state.edit(storePeer) { reconcilePage(storePeer, page, it) }
        }
    }

    /** A page snapshot (or an unopened record's seq − 1) below the cursor or a running catch-up's floor pulls them back (MC:1282-1301). */
    private fun lowerCatchUp(storePeer: UUID, value: Long) {
        cursors()[storePeer]?.let { cursor -> if (value < cursor) saveCursor(value, storePeer) }
        catchUpFloors[storePeer]?.let { floor -> if (value < floor) catchUpFloors[storePeer] = value }
    }

    /**
     * Opens changes and applies them to the messages this device holds (`applyReactionChanges`,
     * MC:5448-5486); a change for a message it does not hold waits for that message's page. Throws,
     * applying nothing, when a sender's key is out of reach. Returns the lowest `seq` left alone
     * because our own save for that message is in flight.
     */
    private suspend fun applyChanges(dtos: List<ReactionDto>, storePeer: UUID): Long? {
        val me = state.myUserId ?: return null
        if (state.session == null || !deps.keyring.isUnlocked) return null
        val held = state.messages(storePeer) ?: return null
        val heldById = HashMap<UUID, ChatMessage>()
        for (message in held) heldById.putIfAbsent(message.id, message)
        // Only the two people in this chat react in it (in Notes, only us) (MC:5455-5456).
        val reactors = if (state.isNotes(storePeer)) setOf(me) else setOf(me, storePeer)
        val generation = state.lockGeneration
        // Decrypt first, then apply to the thread as it is by then (MC:5457-5463).
        val opened = ArrayList<Pair<UUID, MessageReaction>>()
        for (dto in dtos) {
            if (dto.userId !in reactors) continue
            val message = heldById[dto.messageId] ?: continue
            if (message.deleted) continue
            opened += dto.messageId to open(dto, message.reactions, me)
        }
        if (opened.isEmpty() || state.lockGeneration != generation || state.messages(storePeer) == null) return null
        var changed = false
        var skippedPending: Long? = null
        state.edit(storePeer) { thread ->
            changed = false
            skippedPending = null
            val list = thread.toMutableList()
            val indexById = HashMap<UUID, Int>()
            list.forEachIndexed { index, message -> indexById.putIfAbsent(message.id, index) }
            for ((messageId, entry) in opened) {
                val index = indexById[messageId] ?: continue
                if (list[index].deleted) continue
                if (list[index].reactions.any { it.userId == entry.userId && it.pending }) {
                    skippedPending = minOf(skippedPending ?: entry.seq, entry.seq)
                    continue
                }
                val updated = ReactionMerge.apply(entry, list[index].reactions) ?: continue
                list[index] = list[index].copy(reactions = updated)
                changed = true
            }
            if (changed) list else thread
        }
        if (!changed) return skippedPending
        noteChanged(storePeer)
        schedulePersist(storePeer)
        return skippedPending
    }

    /**
     * `message.reaction` from the peer or our other device (`handleReactionEvent`, MC:5492-5510).
     * Never moves the catch-up cursor: an event lost on the socket comes back through catch-up, and
     * applying one twice is harmless.
     */
    override fun apply(event: RealtimeEvent.MessageReaction) {
        val dto = event.reaction
        // The conversation names the chat; Notes (not in the list) falls back to a scan (MC:5500-5502).
        val storePeer = event.conversationId?.let { id -> host().conversations.firstOrNull { it.id == id }?.peer?.id }
            ?: state.peerFor(dto.messageId)
        scope.launch {
            if (storePeer != null) {
                try {
                    applyChanges(listOf(dto), storePeer)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A sender's key out of reach: catch-up brings the change back (MC:5504-5507).
                }
            }
            noteActivity(dto, storePeer, event.messageSenderId, event.added)
        }
    }

    /**
     * The other side reacted to (or took back a reaction on) one of our messages: the open chat marks
     * it seen, any other chat's badge is re-read from the server; a banner for news
     * (`noteReactionActivity`, MC:5515-5547). Taking back one emoji of several is no news.
     */
    private fun noteActivity(dto: ReactionDto, storePeer: UUID?, messageSenderId: UUID?, added: Boolean) {
        val me = state.myUserId ?: return
        if (dto.userId == me) return
        if (messageSenderId != null && messageSenderId != me) return
        if (!added && dto.ciphertext != null) return
        val host = host()
        if (added && dto.ciphertext != null && storePeer != null && !state.isNotes(storePeer)) {
            deps.notifier()?.announce(
                kind = NotificationKind.Reaction,
                peerUserId = storePeer,
                username = host.username(storePeer),
                conversationId = host.conversations.firstOrNull { it.peer.id == storePeer }?.id,
                text = null,
                muted = host.isMuted(storePeer),
            )
        }
        if (storePeer != null && storePeer == host.activePeerId) {
            if (!added) return
            host.editConversations { list ->
                list.map { if (it.peer.id == storePeer && (it.reactionSeq ?: 0) < dto.seq) it.copy(reactionSeq = dto.seq) else it }
            }
            markSeen(storePeer, dto.seq)
        } else {
            refreshConversationsSoon()
        }
    }

    /** `reactions.seen` from our other device (`handleReactionsSeenEvent`, MC:5550-5559). */
    override fun apply(event: RealtimeEvent.ReactionsSeen) {
        seenLocally[event.peerUserId] = maxOf(seenLocally[event.peerUserId] ?: 0, event.seenSeq)
        val host = host()
        val before = host.conversations
        val after = applyLocalSeen(before)
        if (after != before) host.editConversations { applyLocalSeen(it) } else refreshConversationsSoon()
    }

    // ---- catch-up ----

    /** [catchUp] from the newest page snapshot [applyPage] / [openPage] saw for the chat; none → nothing to do. */
    override suspend fun catchUp(storePeer: UUID) {
        val snapshot = pageSnapshots[storePeer] ?: return
        catchUp(storePeer, snapshot)
    }

    /**
     * Brings reactions on messages this device holds up to date after a refresh (`catchUpReactions`,
     * MC:5912-5955): from the chat's cursor (0 when none), five pages of 200 per refresh; the cursor
     * stops at a page snapshot published meanwhile and before a change of ours left alone while our
     * save was in flight.
     */
    suspend fun catchUp(storePeer: UUID, newestSnapshot: Long) {
        val token = state.session?.token ?: return
        val start = cursors()[storePeer] ?: 0
        if (start >= newestSnapshot) return
        catchUpFloors[storePeer] = Long.MAX_VALUE
        try {
            var after = start
            var skippedPending: Long? = null
            for (round in 0 until CATCH_UP_PAGES) {
                val response = try {
                    deps.api.reactionChanges(token, state.apiPeer(storePeer), after, CATCH_UP_PAGE_SIZE)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    break
                }
                if (response.reactions.isNotEmpty()) {
                    // A sender's key out of reach: stop before these, the next refresh retries them (MC:5934-5941).
                    val skipped = try {
                        applyChanges(response.reactions, storePeer)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        break
                    }
                    if (skipped != null) skippedPending = minOf(skippedPending ?: skipped, skipped)
                }
                after = response.nextSeq
                if (!response.hasMore) break
            }
            var end = minOf(after, catchUpFloors[storePeer] ?: after)
            skippedPending?.let { end = minOf(end, it - 1) }
            val current = cursors()[storePeer]
            if (end > start && (current == null || current == start)) saveCursor(end, storePeer)
        } finally {
            catchUpFloors.remove(storePeer)
        }
    }

    /** Empty — and not cached — while the file cannot be read: catch-up then starts from zero (MC:5889-5894). */
    private fun cursors(): Map<UUID, Long> {
        cursorCache?.let { return it }
        val me = state.myUserId ?: return emptyMap()
        val loaded = deps.store().reactionCursors(me) ?: return emptyMap()
        cursorCache = loaded
        return loaded
    }

    /** Never writes a map that was not read: it would reset every other chat's cursor (MC:5896-5903). */
    private fun saveCursor(seq: Long, storePeer: UUID) {
        cursors()
        val cursors = cursorCache ?: return
        if (cursors[storePeer] == seq) return
        val me = state.myUserId ?: return
        val updated = cursors + (storePeer to seq)
        cursorCache = updated
        deps.store().saveReactionCursors(me, updated)
    }

    // ---- heart badge ----

    /** The other side reacted to our messages since we last looked; never for the open chat (`hasUnseenReactions`, MC:5563-5570). */
    override fun hasUnseen(storePeer: UUID): Boolean {
        val host = host()
        if (host.activePeerId == storePeer) return false
        return hasPendingUnseen(storePeer, host.conversations)
    }

    private fun hasPendingUnseen(storePeer: UUID, list: List<ConversationItemDto>): Boolean =
        (list.firstOrNull { it.peer.id == storePeer }?.unseenReactions ?: 0) > 0

    /**
     * The open chat's list row or thread was just refreshed: while the list still counts unseen
     * reactions for it, they are marked seen (MC:1047-1048 after a list refresh, MC:1408-1409 after
     * a thread load). Another chat than the open one is left alone. `MessagingController` calls it
     * at both places — W2-MSG-CORE's `ReactionsEngine.onChatShown` (contract change request CR-2).
     */
    fun onChatShown(storePeer: UUID) {
        val host = host()
        if (host.activePeerId != storePeer || !hasPendingUnseen(storePeer, host.conversations)) return
        markSeen(storePeer)
    }

    /**
     * Clears the chat's badge here and asks the server to clear it everywhere (`markReactionsSeen`,
     * MC:5576-5595): up to [upTo], the list's `reaction_seq` or the cursor, whichever is further. Not
     * saved → the local mark is forgotten so the badge comes back and the next open retries.
     */
    fun markSeen(storePeer: UUID, upTo: Long? = null) {
        if (state.isNotes(storePeer)) return
        val token = state.session?.token ?: return
        val host = host()
        val listed = host.conversations.firstOrNull { it.peer.id == storePeer }?.reactionSeq ?: 0
        val seq = maxOf(upTo ?: 0, listed, cursors()[storePeer] ?: 0)
        if (seq <= 0) return
        seenLocally[storePeer] = maxOf(seenLocally[storePeer] ?: 0, seq)
        host.editConversations { applyLocalSeen(it) }
        val apiPeer = state.apiPeer(storePeer)
        scope.launch {
            try {
                deps.api.markReactionsSeen(token, apiPeer, seq)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (seenLocally[storePeer] == seq) seenLocally.remove(storePeer)
            }
        }
    }

    /**
     * Zeroes badges this device already marked seen, for a list that may predate the seen call
     * (`applyingLocalReactionSeen`, MC:5598-5609). `MessagingController.refreshConversations` applies it
     * to every list it adopts — W2-MSG-CORE's `ReactionsEngine.applyLocalSeen` (contract change
     * request CR-2).
     */
    fun applyLocalSeen(conversations: List<ConversationItemDto>): List<ConversationItemDto> {
        if (seenLocally.isEmpty()) return conversations
        return conversations.map { item ->
            val seen = seenLocally[item.peer.id] ?: return@map item
            if ((item.unseenReactions ?: 0) <= 0 || (item.reactionSeq ?: 0) > seen) return@map item
            item.copy(unseenReactions = 0)
        }
    }

    /** One conversations refresh for a burst of reaction events (`refreshConversationsSoon`, MC:5856-5864). */
    private fun refreshConversationsSoon() {
        if (refreshSoonJob != null) return
        refreshSoonJob = scope.launch {
            delay(timing.refreshSoonDelayMs)
            refreshSoonJob = null
            host().refreshConversations(force = true)
        }
    }

    // ---- config, persistence, lifecycle ----

    /** `GET /config` → the reaction limit, remembered for offline starts (`refreshServerConfig`, MC:5112-5120). */
    override suspend fun refreshServerConfig() {
        val token = state.session?.token ?: return
        val config = try {
            deps.api.clientConfig(token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val newLimit = maxOf(1, config.reactions.maxPerUser)
        if (newLimit == limitFlow.value) return
        limitFlow.value = newLimit
        if (!storageSeal.isSealed) prefs.edit { putInt(LIMIT_KEY, newLimit) }
    }

    /** Batches the sealed thread save after reaction changes: events come in bursts (`scheduleReactionPersist`, MC:5868-5876). */
    private fun schedulePersist(storePeer: UUID) {
        persistJobs.remove(storePeer)?.cancel()
        val save = scope.launch(start = CoroutineStart.LAZY) {
            delay(timing.persistDelayMs)
            persistJobs.remove(storePeer)
            state.persistThread(storePeer)
        }
        persistJobs[storePeer] = save
        save.start()
    }

    /**
     * Chats lock: batched saves go to disk before the key leaves, and the cursor cache is dropped
     * (`flushReactionPersists` + `reactionCursorCache = nil`, MC:5878-5885, 657-666).
     */
    override suspend fun flushPendingSaves() {
        val pending = persistJobs.toMap()
        persistJobs.clear()
        for ((storePeer, save) in pending) {
            save.cancel()
            state.persistThread(storePeer)
        }
        cursorCache = null
    }

    /**
     * Sign-out, wipe, stop: cancels saves and batched writes and forgets every per-account memory
     * (`cancelReactionWork` + the reaction part of `clearInMemoryState`, MC:5301-5312, 566-586).
     */
    override fun reset() {
        job.cancelChildren()
        sendJobs.clear()
        intents.clear()
        rollback.clear()
        persistJobs.clear()
        catchUpFloors.clear()
        refreshSoonJob = null
        cursorCache = null
        seenLocally.clear()
        pageSnapshots.clear()
        revisionFlow.value = emptyMap()
    }

    private fun noteChanged(storePeer: UUID) {
        revisionFlow.update { it + (storePeer to (it[storePeer] ?: 0) + 1) }
    }

    companion object {
        /** `shroud.messaging` key of the limit (MC:171); wiped at Log Out with the file (plan §1.5). */
        const val LIMIT_KEY = "shroud.reactions.maxPerUser"
        const val DEFAULT_LIMIT = 5

        /** Rounds a save may lose to our other device writing first (MC:5157). */
        const val MAX_REBASES = 3

        /** Catch-up pages per refresh and their size (MC:5927, `MessagesService.swift:136`). */
        const val CATCH_UP_PAGES = 5
        const val CATCH_UP_PAGE_SIZE = 200

        const val COULD_NOT_SAVE = "Couldn't save your reaction."
        const val OFFLINE_NOT_SAVED = "You're offline. Your reaction wasn't saved."

        /**
         * No answer yet rather than a "no" (`isTransient`, MC:5430-5437): cancelled, offline, the server
         * struggling or asking us to slow down — and, on Android, a pin that cannot be read while the
         * phone is locked (`CryptoError.Locked`, plan §1.7.4 note).
         */
        internal fun isTransient(error: Throwable): Boolean = when (error) {
            is CancellationException, is IOException, is CryptoError.Locked -> true
            is ApiError.Transport -> true
            is ApiError.Server -> error.status >= 500 || error.status == 429
            else -> false
        }
    }
}
