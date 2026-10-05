package de.corespace.shroud.core.messaging

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.contacts.PeerIdentities
import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.InMemoryRatchetSessionRecords
import de.corespace.shroud.core.crypto.InMemorySenderTagWatermarks
import de.corespace.shroud.core.crypto.MediaCrypto
import de.corespace.shroud.core.crypto.MessageCrypto
import de.corespace.shroud.core.crypto.OpenAs
import de.corespace.shroud.core.crypto.PeerLocks
import de.corespace.shroud.core.crypto.TestIdentity
import de.corespace.shroud.core.media.EncodedImage
import de.corespace.shroud.core.media.EncodedVideo
import de.corespace.shroud.core.media.ImagePipeline
import de.corespace.shroud.core.media.LocalMediaStore
import de.corespace.shroud.core.media.MediaComposeQuality
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.MediaTransfers
import de.corespace.shroud.core.media.PlainSource
import de.corespace.shroud.core.media.SealedMediaReader
import de.corespace.shroud.core.media.SealedMediaWriter
import de.corespace.shroud.core.media.UploadedBlob
import de.corespace.shroud.core.media.VideoPipeline
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.files.Shrf1
import de.corespace.shroud.core.media.video.VideoSendPlan
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.model.ChatMessageKind
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.model.MediaTransfer
import de.corespace.shroud.core.model.NOTES_PEER_ID
import de.corespace.shroud.core.model.PeerIdentityChange
import de.corespace.shroud.core.model.PeerIdentityChangedException
import de.corespace.shroud.core.model.PeerIdentityEvent
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ClientConfigDto
import de.corespace.shroud.core.net.ContentType
import de.corespace.shroud.core.net.ConversationItemDto
import de.corespace.shroud.core.net.ListMessagesResponse
import de.corespace.shroud.core.net.MarkReactionsSeenResponse
import de.corespace.shroud.core.net.MessageDto
import de.corespace.shroud.core.net.ReactionChangesResponse
import de.corespace.shroud.core.net.ReactionDto
import de.corespace.shroud.core.net.ReactionWriteResult
import de.corespace.shroud.core.net.SendMessageRequest
import de.corespace.shroud.core.notifications.MessageNotifier
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.testing.FakeAppClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

// Fakes for the W2-MSG-SEND engines: the seams of plan §1.7.7–§1.7.9 in memory, a scripted server
// that re-keys like the real one (memory: *Server re-keys sent messages*) and real crypto, so every
// sealed envelope is opened by the other side in the tests.

/**
 * [ThreadState] in memory, with the contract `ThreadStore` (W2-MSG-CORE) publishes: [rekey] replaces
 * the optimistic bubble in place, drops the optimistic id's caches, moves its transfer and tells the
 * sinks; [purge] drops caches. [lock] behaves like `lockSensitiveMemory`.
 */
class SendFakeThreadState(
    me: UUID?,
    token: String?,
    private val store: SendFakeMessagingStore,
) : ThreadState {
    override var myUserId: UUID? = me
    override var session: Session? = token?.let { Session(it, Ids.wire(me!!), "noah", null, Ids.wire(UUID.randomUUID())) }
    override var lockGeneration: Long = 0
    private val flow = MutableStateFlow<Map<UUID, List<ChatMessage>>>(emptyMap())
    override val threads: StateFlow<Map<UUID, List<ChatMessage>>> = flow
    override val transfers = SendFakeTransferBoard()
    override val isOffline: Boolean = false
    override val isRealtimeConnected: Boolean = false

    var persistThreads = mutableListOf<UUID>()
    var snapshots = 0
    var shownError: String? = null
    val purged = mutableListOf<UUID>()
    val rekeys = mutableListOf<Pair<UUID, UUID>>()

    override fun messages(storePeer: UUID): List<ChatMessage>? = flow.value[storePeer]

    override fun edit(storePeer: UUID, transform: (List<ChatMessage>) -> List<ChatMessage>) {
        val current = flow.value[storePeer]
        val next = transform(current.orEmpty())
        if (current == null || next != current) flow.value = flow.value + (storePeer to next)
    }

    override fun update(messageId: UUID, transform: (ChatMessage) -> ChatMessage): Boolean {
        for ((peer, list) in flow.value) {
            val index = list.indexOfFirst { it.id == messageId }
            if (index < 0) continue
            val updated = transform(list[index])
            if (updated != list[index]) flow.value = flow.value + (peer to list.toMutableList().also { it[index] = updated })
            return true
        }
        return false
    }

    override fun rekey(storePeer: UUID, optimisticId: UUID, sent: ChatMessage) {
        edit(storePeer) { list ->
            val index = list.indexOfFirst { it.id == optimisticId }
            if (index < 0) {
                if (list.any { it.id == sent.id }) list else list + sent
            } else {
                list.filterIndexed { i, m -> i == index || m.id != sent.id }.map { if (it.id == optimisticId) sent else it }
            }
        }
        store.removeCaches(listOf(optimisticId))
        transfers.rekey(optimisticId, sent.id)
        rekeys += optimisticId to sent.id
    }

    override fun peerFor(messageId: UUID): UUID? = flow.value.entries.firstOrNull { (_, list) -> list.any { it.id == messageId } }?.key
    override fun storePeer(apiPeer: UUID): UUID = if (apiPeer == myUserId) NOTES_PEER_ID else apiPeer
    override fun apiPeer(storePeer: UUID): UUID = if (storePeer == NOTES_PEER_ID) myUserId ?: storePeer else storePeer
    override fun isNotes(storePeer: UUID): Boolean = storePeer == NOTES_PEER_ID

    override fun persistThread(storePeer: UUID) {
        persistThreads += storePeer
    }

    override fun persistSnapshot() {
        snapshots++
    }

    override fun purge(messageIds: Collection<UUID>) {
        store.removeCaches(messageIds)
        purged += messageIds
    }

    override fun setLastError(message: String?) {
        shownError = message
    }

    /** `lockSensitiveMemory`: threads go, the generation moves. */
    fun lock() {
        lockGeneration++
        flow.value = emptyMap()
    }

    fun put(storePeer: UUID, messages: List<ChatMessage>) {
        flow.value = flow.value + (storePeer to messages)
    }
}

/** [MediaTransferBoard] that also records every phase it went through. */
class SendFakeTransferBoard : MediaTransferBoard {
    private val flow = MutableStateFlow<Map<UUID, MediaTransfer>>(emptyMap())
    override val transfers: StateFlow<Map<UUID, MediaTransfer>> = flow
    val phases = mutableListOf<Pair<UUID, MediaTransfer.Phase?>>()
    val fractions = mutableListOf<Double>()

    override fun begin(id: UUID, isUpload: Boolean) {
        flow.value = flow.value + (id to MediaTransfer(MediaTransfer.Phase.Preparing, isUpload))
    }

    override fun advance(id: UUID, phase: MediaTransfer.Phase, totalBytes: Long?) {
        val current = flow.value[id] ?: return
        flow.value = flow.value + (id to current.copy(phase = phase, fraction = null, totalBytes = totalBytes ?: current.totalBytes))
        phases += id to phase
    }

    override fun update(id: UUID, fraction: Double) {
        val current = flow.value[id] ?: return
        fractions += fraction
        flow.value = flow.value + (id to current.copy(fraction = fraction.coerceIn(0.0, 1.0)))
    }

    override fun end(id: UUID) {
        if (id in flow.value) phases += id to null
        flow.value = flow.value - id
    }

    override fun rekey(from: UUID, to: UUID) {
        val moving = flow.value[from] ?: return
        flow.value = flow.value - from + (to to moving)
    }
}

/** [LocalMediaStore] in memory (SHRM1 is W2-MEDIA-STORE's; only the contract matters here). */
class SendFakeMediaStore : LocalMediaStore {
    val files = ConcurrentHashMap<UUID, ByteArray>()
    val renames = mutableListOf<Pair<UUID, UUID>>()
    var refuseSaves = false

    override suspend fun readAll(messageId: UUID): ByteArray? = files[messageId]?.copyOf()
    override fun has(messageId: UUID): Boolean = files.containsKey(messageId)

    override suspend fun save(messageId: UUID, data: ByteArray) {
        if (refuseSaves) throw IOException("disk full")
        files[messageId] = data.copyOf()
    }

    override fun writer(messageId: UUID): SealedMediaWriter = object : SealedMediaWriter {
        private val buffer = ByteArrayOutputStream()
        override fun write(buffer: ByteArray, offset: Int, size: Int) = this.buffer.write(buffer, offset, size)
        override fun commit() {
            files[messageId] = buffer.toByteArray()
        }
        override fun abort() = buffer.reset()
        override fun close() = Unit
    }

    override fun openReader(messageId: UUID): SealedMediaReader? {
        val data = files[messageId] ?: return null
        return object : SealedMediaReader {
            override val length: Long = data.size.toLong()
            override fun read(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= data.size) return -1
                val count = minOf(size, data.size - position.toInt())
                System.arraycopy(data, position.toInt(), buffer, offset, count)
                return count
            }
            override fun close() = Unit
        }
    }

    override fun rename(from: UUID, to: UUID) {
        val data = files.remove(from) ?: return
        files[to] = data
        renames += from to to
    }

    override fun remove(messageIds: Collection<UUID>) {
        messageIds.forEach { files.remove(it) }
    }

    override fun clearAll() = files.clear()
    override fun inventory(): Pair<Int, Long> = files.size to files.values.sumOf { it.size.toLong() }
}

/** [MessagingStore] in memory: plaintext, annotation index (D5), reaction cursors; [removeCaches] reaches the media store. */
class SendFakeMessagingStore(private val media: SendFakeMediaStore) : MessagingStore {
    val plaintexts = ConcurrentHashMap<UUID, ByteArray>()
    val annotations = HashMap<UUID, MutableSet<UUID>>()
    var cursors: Map<UUID, Long> = emptyMap()

    /** False: the cursor file exists but does not open (MC:5887-5888). */
    var cursorsReadable = true
    val savedCursors = mutableListOf<Map<UUID, Long>>()
    var cursorReads = 0

    override fun hydrate(userId: UUID) = HydratedMessages(RosterSnapshot(emptyList(), emptyList(), emptyList(), emptyMap()), emptyMap())
    override fun persist(userId: UUID, snapshot: MessagingSnapshot): Set<UUID> = emptySet()
    override fun persistThread(userId: UUID, storePeer: UUID, messages: List<ChatMessage>, roster: RosterSnapshot): Set<UUID> = emptySet()
    override suspend fun flush() = Unit
    /** The sender each entry was saved for; an entry a test put in [plaintexts] by hand fits any sender. */
    val plaintextSenders = ConcurrentHashMap<UUID, UUID>()

    override fun plaintext(messageId: UUID, senderUserId: UUID): ByteArray? =
        plaintexts[messageId]?.takeIf { plaintextSenders[messageId].let { it == null || it == senderUserId } }?.copyOf()

    override fun savePlaintext(messageId: UUID, senderUserId: UUID, bytes: ByteArray) {
        plaintexts[messageId] = bytes.copyOf()
        plaintextSenders[messageId] = senderUserId
    }

    /** A test's by-hand entry, fitting any sender. */
    fun savePlaintext(messageId: UUID, bytes: ByteArray) {
        plaintexts[messageId] = bytes.copyOf()
        plaintextSenders.remove(messageId)
    }

    override fun removeCaches(messageIds: Collection<UUID>) {
        for (id in messageIds) {
            plaintexts.remove(id)
            annotations.remove(id)?.forEach { plaintexts.remove(it) }
        }
        media.remove(messageIds)
    }

    override fun noteAnnotation(targetId: UUID, annotationId: UUID) {
        annotations.getOrPut(targetId) { mutableSetOf() } += annotationId
    }

    override fun annotationsFor(targetId: UUID): Set<UUID> = annotations[targetId].orEmpty()

    override fun reactionCursors(userId: UUID): Map<UUID, Long>? {
        cursorReads++
        return if (cursorsReadable) cursors else null
    }

    override fun saveReactionCursors(userId: UUID, cursors: Map<UUID, Long>) {
        this.cursors = cursors
        savedCursors += cursors
    }

    override fun lockSensitiveMemory() = Unit
    override fun clear(userId: UUID?) = Unit

    fun plaintextString(messageId: UUID): String? = plaintexts[messageId]?.toString(Charsets.UTF_8)
}

/**
 * The server, scripted: `POST /messages` mints its own id and is idempotent by `client_message_id`
 * (`messages.rs`), history pages are newest first, reaction writes follow `reactions.rs`
 * (`base_seq` 0 matches a removal; a live record not built on → 409 with `current`).
 */
class SendFakeServer(private val me: UUID) : SendApi {
    private var clock = Instant.parse("2026-09-24T12:00:00Z")
    private val byClientId = HashMap<UUID, MessageDto>()
    val messages = mutableListOf<MessageDto>()
    val requests = mutableListOf<SendMessageRequest>()

    /** Thrown by the next sends, in order. */
    val sendFailures = ArrayDeque<Throwable>()

    /** The next send is stored, but its answer is lost (a timeout after the server committed). */
    var loseNextAnswer = false

    var reactionLimit = 5
    private var seq = 40L
    val records = HashMap<Pair<UUID, UUID>, ReactionDto>()
    val changeLog = mutableListOf<ReactionDto>()
    val writes = mutableListOf<Write>()

    /** Scripted 409s: our other device wrote these records first. */
    val conflicts = ArrayDeque<ReactionDto>()
    var reactionFailures = 0

    /** Thrown by the next reaction writes, in order, before anything else (a 404, a 409 without a record). */
    val reactionErrors = ArrayDeque<Throwable>()
    var changesPagesServed = 0
    val seenCalls = mutableListOf<Pair<UUID, Long>>()
    var seenFails = false

    data class Write(val messageId: UUID, val ciphertext: ByteArray?, val baseSeq: Long, val added: Boolean?)

    fun conversationId(a: UUID, b: UUID): UUID = UUID.nameUUIDFromBytes(listOf(Ids.wire(a), Ids.wire(b)).sorted().joinToString("+").toByteArray())

    /** The server's `created_at`: microseconds, one second apart. */
    private fun nextWire(): String {
        clock = clock.plusSeconds(1)
        return WIRE.format(clock) + ".123456Z"
    }

    private companion object {
        val WIRE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC)
    }

    /** Stores a message as if [sender] had sent it to [recipient]. */
    fun store(sender: UUID, recipient: UUID, contentType: String, ciphertext: String, mediaObjectId: UUID? = null, clientId: UUID = UUID.randomUUID()): MessageDto {
        val dto = MessageDto(
            id = UUID.randomUUID(),
            conversationId = conversationId(sender, recipient),
            senderUserId = sender,
            senderDeviceId = UUID.randomUUID(),
            clientMessageId = clientId,
            contentType = contentType,
            ciphertext = ciphertext,
            mediaObjectId = mediaObjectId,
            deletedForEveryone = false,
            createdAtWire = nextWire(),
            delivered = false,
            read = false,
        )
        messages += dto
        return dto
    }

    /** When set, a send waits for it after the request went out (an answer "in flight"). */
    var sendGate: CompletableDeferred<Unit>? = null

    override suspend fun sendMessage(token: String, request: SendMessageRequest): MessageDto {
        requests += request
        sendGate?.await()
        sendFailures.removeFirstOrNull()?.let { throw it }
        byClientId[request.clientMessageId]?.let { return it }
        val dto = store(me, request.peerUserId, request.contentType, request.ciphertext, request.mediaObjectId, request.clientMessageId)
        byClientId[request.clientMessageId] = dto
        if (loseNextAnswer) {
            loseNextAnswer = false
            throw ApiError.Transport("The request timed out.")
        }
        return dto
    }

    override suspend fun messages(token: String, peerUserId: UUID, limit: Int): ListMessagesResponse {
        val conversation = conversationId(me, peerUserId)
        val page = messages.filter { it.conversationId == conversation }.reversed().take(limit)
        return ListMessagesResponse(conversation, page, false, seq)
    }

    override suspend fun clientConfig(token: String) = ClientConfigDto(ClientConfigDto.Reactions(reactionLimit))

    private fun record(messageId: UUID, ciphertext: ByteArray?): ReactionDto {
        // The chat's seq only grows: past any record a test (or our "other device") put there first.
        seq = maxOf(seq, records.values.maxOfOrNull { it.seq } ?: 0L) + 1
        val dto = ReactionDto(messageId, me, ciphertext?.let(B64::encode), seq, Instant.parse("2026-09-23T21:08:35.759754Z"))
        records[messageId to me] = dto
        changeLog += dto
        return dto
    }

    /** When set, a reaction write waits for it after it went out. */
    var reactionGate: CompletableDeferred<Unit>? = null

    override suspend fun putReaction(token: String, messageId: UUID, ciphertext: ByteArray, baseSeq: Long, added: Boolean): ReactionWriteResult {
        writes += Write(messageId, ciphertext, baseSeq, added)
        reactionGate?.await()
        reactionErrors.removeFirstOrNull()?.let { throw it }
        if (reactionFailures > 0) {
            reactionFailures--
            throw ApiError.Transport("The network connection was lost.")
        }
        conflicts.removeFirstOrNull()?.let {
            records[messageId to me] = it
            return ReactionWriteResult.ChangedElsewhere(it)
        }
        val current = records[messageId to me]
        if (current != null && current.seq != baseSeq && !(baseSeq == 0L && current.ciphertext == null)) {
            return ReactionWriteResult.ChangedElsewhere(current)
        }
        return ReactionWriteResult.Saved(record(messageId, ciphertext))
    }

    override suspend fun deleteReaction(token: String, messageId: UUID, baseSeq: Long): ReactionWriteResult {
        writes += Write(messageId, null, baseSeq, null)
        reactionErrors.removeFirstOrNull()?.let { throw it }
        if (reactionFailures > 0) {
            reactionFailures--
            throw ApiError.Transport("The network connection was lost.")
        }
        conflicts.removeFirstOrNull()?.let {
            records[messageId to me] = it
            return ReactionWriteResult.ChangedElsewhere(it)
        }
        val current = records[messageId to me]
        if (current == null || current.ciphertext == null) return ReactionWriteResult.Saved(null)
        if (current.seq != baseSeq) return ReactionWriteResult.ChangedElsewhere(current)
        return ReactionWriteResult.Saved(record(messageId, null))
    }

    override suspend fun reactionChanges(token: String, peerUserId: UUID, afterSeq: Long, limit: Int): ReactionChangesResponse {
        changesPagesServed++
        val after = changeLog.filter { it.seq > afterSeq }.sortedBy { it.seq }
        val page = after.take(limit)
        return ReactionChangesResponse(page, page.lastOrNull()?.seq ?: afterSeq, after.size > page.size)
    }

    override suspend fun markReactionsSeen(token: String, peerUserId: UUID, upToSeq: Long): MarkReactionsSeenResponse {
        seenCalls += peerUserId to upToSeq
        if (seenFails) throw ApiError.Transport("offline")
        return MarkReactionsSeenResponse(upToSeq)
    }

    fun lastRequest(): SendMessageRequest = requests.last()
    fun dtoFor(request: SendMessageRequest): MessageDto = byClientId.getValue(request.clientMessageId)
}

/** [MediaTransfers] sealing for real ([MediaCrypto]), blobs kept in memory; downloads land in the media store. */
class SendFakeMediaTransfers(private val media: SendFakeMediaStore) : MediaTransfers {
    val blobs = HashMap<UUID, ByteArray>()
    val uploads = mutableListOf<PlainSource>()
    var failUploads = 0
    var downloads = 0

    /** When set, a download waits for it (a download "in flight"). */
    var gate: CompletableDeferred<Unit>? = null

    /** When set, every upload waits for it after it started (an upload "in flight"). */
    var uploadGate: CompletableDeferred<Unit>? = null

    override suspend fun upload(source: PlainSource, token: String, onProgress: ((Double) -> Unit)?): UploadedBlob {
        uploads += source
        uploadGate?.await()
        if (failUploads > 0) {
            failUploads--
            throw ApiError.Transport("The network connection was lost.")
        }
        val plain = when (source) {
            is PlainSource.InMemory -> source.data
            is PlainSource.LocalMedia -> media.files[source.messageId] ?: throw IOException("not cached")
            is PlainSource.TempFile -> source.file.readBytes()
        }
        val sealed = MediaCrypto.sealFile(plain)
        val id = UUID.randomUUID()
        blobs[id] = sealed.sealed
        onProgress?.invoke(0.5)
        onProgress?.invoke(1.0)
        return UploadedBlob(id, B64.encode(sealed.key), plain.size.toLong(), sealed.sealed.size.toLong())
    }

    override suspend fun downloadInto(mediaObjectId: UUID, keyBase64: String, token: String, targetMessageId: UUID, onProgress: ((Double) -> Unit)?) {
        downloads++
        gate?.await()
        val sealed = blobs[mediaObjectId] ?: throw ApiError.Server("NOT_FOUND", "Media content not found.", 404)
        val key = B64.decodeStrict(keyBase64) ?: throw CryptoError.OpenFailed
        val plain = MediaCrypto.openFile(sealed, key)
        onProgress?.invoke(1.0)
        media.save(targetMessageId, plain)
    }

    /** SHRF1 uploads (docs/file-sharing.md §3), sealed for real with [Shrf1]. */
    val fileUploads = mutableListOf<PlainSource>()
    var fileDownloads = 0

    override suspend fun uploadFile(source: PlainSource, token: String, onProgress: ((Double) -> Unit)?): UploadedBlob {
        fileUploads += source
        uploadGate?.await()
        if (failUploads > 0) {
            failUploads--
            throw ApiError.Transport("The network connection was lost.")
        }
        val plain = when (source) {
            is PlainSource.InMemory -> source.data
            is PlainSource.LocalMedia -> media.files[source.messageId] ?: throw IOException("not cached")
            is PlainSource.TempFile -> source.file.readBytes()
        }
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val prefix = ByteArray(Shrf1.NONCE_PREFIX_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        val out = ByteArrayOutputStream()
        Shrf1.seal(plain.inputStream(), plain.size.toLong(), out, key, prefix)
        val id = UUID.randomUUID()
        blobs[id] = out.toByteArray()
        onProgress?.invoke(0.5)
        onProgress?.invoke(1.0)
        return UploadedBlob(id, B64.encode(key), plain.size.toLong(), blobs.getValue(id).size.toLong())
    }

    override suspend fun downloadFileInto(
        mediaObjectId: UUID,
        keyBase64: String,
        plainSize: Long,
        token: String,
        targetMessageId: UUID,
        onProgress: ((Double) -> Unit)?,
    ) {
        fileDownloads++
        gate?.await()
        val sealed = blobs[mediaObjectId] ?: throw ApiError.Server("NOT_FOUND", "Media content not found.", 404)
        val key = B64.decodeStrict(keyBase64) ?: throw CryptoError.OpenFailed
        if (sealed.size.toLong() != Shrf1.sealedSize(plainSize)) throw MediaCrypto.MediaError.DecryptFailed
        val out = ByteArrayOutputStream()
        Shrf1.open(sealed.inputStream(), plainSize, out, key)
        onProgress?.invoke(1.0)
        media.save(targetMessageId, out.toByteArray())
    }

    /** A SHRF1 blob the server holds, opened with [keyBase64]. */
    fun openFile(mediaObjectId: UUID, keyBase64: String, plainSize: Long): ByteArray {
        val out = ByteArrayOutputStream()
        Shrf1.open(blobs.getValue(mediaObjectId).inputStream(), plainSize, out, B64.decodeStrict(keyBase64)!!)
        return out.toByteArray()
    }

    /** What the server holds for [mediaObjectId], opened with [keyBase64]. */
    fun open(mediaObjectId: UUID, keyBase64: String): ByteArray = MediaCrypto.openFile(blobs.getValue(mediaObjectId), B64.decodeStrict(keyBase64)!!)
}

/** [ImagePipeline] returning what the test set. */
class SendFakeImages : ImagePipeline {
    var failure: Throwable? = null
    var width = 4032
    var height = 3024
    var mime = "image/jpeg"
    var preview: ByteArray? = ByteArray(200) { 7 }
    var size: Pair<Int, Int>? = 4032 to 3024

    override suspend fun encode(source: MediaImageSource, quality: MediaComposeQuality, edits: MediaEdits): EncodedImage {
        failure?.let { throw it }
        val bytes = (source as MediaImageSource.FileBytes).bytes
        return EncodedImage(Bytes.of(bytes), width, height, mime)
    }

    override fun chatPreviewJpeg(image: ByteArray): ByteArray? = preview?.copyOf()
    override fun pixelSize(image: ByteArray): Pair<Int, Int>? = size
    override fun mimeType(image: ByteArray): String = mime
}

/** A file-backed [VideoPipeline]: the encode writes a `shroud-*` file like Media3's Transformer output (C28). */
class SendFakeVideo(private val dir: File) : VideoPipeline {
    var failure: Throwable? = null
    var output = ByteArray(300_000) { (it % 251).toByte() }
    var poster: Bytes? = Bytes.of(ByteArray(900) { 3 })
    var posterFromLocal: ByteArray? = ByteArray(500) { 9 }
    val posterRequests = mutableListOf<Pair<UUID, Int>>()
    var lastFile: File? = null

    override suspend fun encode(plan: VideoSendPlan, onProgress: ((Double) -> Unit)?): EncodedVideo {
        onProgress?.invoke(0.5)
        failure?.let { throw it }
        val file = File(dir, "shroud-video-${UUID.randomUUID()}.mp4").apply { writeBytes(output) }
        lastFile = file
        onProgress?.invoke(1.0)
        return EncodedVideo(file, 1280, 720, 4_200, "video/mp4", poster, output.size.toLong())
    }

    override suspend fun posterJpegFromLocal(messageId: UUID, maxEdgePx: Int): ByteArray? {
        posterRequests += messageId to maxEdgePx
        return posterFromLocal
    }

    override suspend fun durationMs(messageId: UUID): Int? = null

    override val maxSealedBytes: Long = MediaCrypto.MAX_SEALED_BYTES
}

/** W2-VIDEO's "too large" error as the tests stand it in (CR-4). */
class SendFakeVideoTooLarge : Exception("too large")

/** [PeerIdentities] over fixed keys; [changed] peers throw like a pending key change. */
class SendFakePeerIdentities(private val keys: Map<UUID, ByteArray>) : PeerIdentities {
    val changed = HashSet<UUID>()

    /** Thrown by [resolvePublicKey] (a key out of reach). */
    var resolveFailure: Throwable? = null
    val resolved = mutableListOf<UUID>()

    override val identityChanges: StateFlow<Map<UUID, PeerIdentityChange>> = MutableStateFlow(emptyMap())
    override val verifiedPeers: StateFlow<Set<UUID>> = MutableStateFlow(emptySet())
    override val events: SharedFlow<PeerIdentityEvent> = MutableSharedFlow()

    override suspend fun resolvePublicKey(peer: UUID): ByteArray {
        resolved += peer
        resolveFailure?.let { throw it }
        return keys[peer]?.copyOf() ?: throw ApiError.Server("NOT_FOUND", "User not found.", 404)
    }

    override suspend fun publicKeyForSending(peer: UUID): ByteArray {
        if (peer in changed) throw PeerIdentityChangedException()
        return keys.getValue(peer).copyOf()
    }

    override suspend fun refresh(peer: UUID) = Unit
    override fun identityChange(peer: UUID): PeerIdentityChange? = null
    override fun isSafetyVerified(peer: UUID): Boolean = false
    override fun confirmSafety(peer: UUID) = Unit
    override fun safetyNumber(peer: UUID): String? = null
    override fun acceptNewIdentity(peer: UUID) = Unit
    override fun clearMemory() = Unit
    override fun wipe() = Unit
}

/** [SendKeyring] over a test identity; [locked] = chats locked. */
class SendFakeKeyring(private val identity: TestIdentity) : SendKeyring {
    var locked = false
    override val isUnlocked: Boolean get() = !locked
    override fun <T : Any> withKeys(block: (ourPrivate: ByteArray, ourPublic: ByteArray) -> T): T? =
        if (locked) null else block(identity.private, identity.public)
}

/** [SendHost] in memory. */
class SendFakeHost : SendHost {
    override var conversations: List<ConversationItemDto> = emptyList()
    override var activePeerId: UUID? = null
    var refreshes = 0
    var wentOffline = false
    val muted = HashSet<UUID>()
    val names = HashMap<UUID, String>()

    /** Shared transcripts waiting for their voice note (`pendingSharedTranscripts`). */
    val pendingTranscripts = HashMap<UUID, String>()

    override suspend fun refreshConversations(force: Boolean) {
        refreshes++
    }

    override fun setOffline(offline: Boolean) {
        wentOffline = offline
    }

    override fun isMuted(storePeer: UUID): Boolean = storePeer in muted
    override fun username(storePeer: UUID): String? = names[storePeer]

    override fun editConversations(transform: (List<ConversationItemDto>) -> List<ConversationItemDto>) {
        val next = transform(conversations)
        if (next != conversations) conversations = next
    }

    /** Stands in for an annotation that arrived before any voice note of the thread. */
    var sharedForAny: String? = null
    var folds = 0

    override fun foldSharedTranscripts(thread: List<ChatMessage>): List<ChatMessage> {
        folds++
        return thread.map { message ->
            val shared = pendingTranscripts[message.id] ?: sharedForAny.takeIf { message.kind == ChatMessageKind.Voice }
            if (shared != null && message.transcript.isNullOrBlank()) {
                pendingTranscripts.remove(message.id)
                message.copy(transcript = shared)
            } else {
                message
            }
        }
    }
}

/** [MessageNotifier] that records announcements. */
class SendFakeNotifier : MessageNotifier {
    override var activePeerId: UUID? = null
    val announced = mutableListOf<Triple<NotificationKind, UUID?, String?>>()
    override fun announce(kind: NotificationKind, peerUserId: UUID?, username: String?, conversationId: UUID?, text: String?, muted: Boolean) {
        announced += Triple(kind, peerUserId, username)
    }
    override fun clearDelivered(conversationId: UUID) = Unit
    override fun setBadge(count: Int) = Unit
    override fun setPushCoversBackground(covers: Boolean) = Unit
    override val badgeIncludesMuted: Boolean = false
}

/**
 * One signed-in account ([me]) and a peer, with real `MessageCrypto` on both sides, wired the way
 * `MessagingSendModule` wires production.
 */
class SendWorld(
    scope: CoroutineScope,
    dispatcher: CoroutineContext,
    tempDir: File,
    meFirst: Boolean = true,
    val meKeys: TestIdentity = TestIdentity.random(),
    val peerKeys: TestIdentity = TestIdentity.random(),
) {
    private val ids = listOf(UUID.randomUUID(), UUID.randomUUID()).sortedBy { Ids.wire(it) }
    val me: UUID = if (meFirst) ids[0] else ids[1]
    val peer: UUID = if (meFirst) ids[1] else ids[0]
    val media = SendFakeMediaStore()
    val store = SendFakeMessagingStore(media)
    val state = SendFakeThreadState(me, "tok", store)
    val host = SendFakeHost()
    val server = SendFakeServer(me)
    val transfers = SendFakeMediaTransfers(media)
    val images = SendFakeImages()
    val video = SendFakeVideo(tempDir)
    val identities = SendFakePeerIdentities(mapOf(peer to peerKeys.public, me to meKeys.public))
    val keyring = SendFakeKeyring(meKeys)
    val notifier = SendFakeNotifier()
    val clock = FakeAppClock()
    var online = true
    val crypto = MessageCrypto(InMemoryRatchetSessionRecords(), InMemorySenderTagWatermarks())
    val peerCrypto = MessageCrypto(InMemoryRatchetSessionRecords(), InMemorySenderTagWatermarks())

    val deps = SendDependencies(
        api = server,
        keyring = keyring,
        crypto = crypto,
        peerLocks = PeerLocks(),
        peerIdentities = { identities },
        store = { store },
        media = { media },
        transfers = { transfers },
        images = { images },
        video = { video },
        isOnline = { online },
        clock = clock,
        scope = scope,
        compute = dispatcher,
        io = dispatcher,
        notifier = { notifier },
        videoTooLarge = { it is SendFakeVideoTooLarge },
    )

    fun pipeline() = SendPipeline(state, { host }, deps)

    /** The peer opens what we sent them (their own ratchet store). */
    fun openAsPeer(request: SendMessageRequest): ByteArray {
        val dto = server.dtoFor(request)
        return peerCrypto.open(MessageCrypto.fromWire(request.ciphertext)!!, me, peerKeys.private, peerKeys.public, meKeys.public, OpenAs.Recipient, dto.createdAt)
    }

    /** One of our other devices (or we, later) reads our own message through the self box. */
    fun openAsSender(request: SendMessageRequest): ByteArray =
        crypto.openLegacy(MessageCrypto.fromWire(request.ciphertext)!!, meKeys.private, meKeys.public, meKeys.public, OpenAs.Sender, Instant.now())

    /** The peer seals [plaintext] to us and the server stores it. */
    fun peerSends(plaintext: ByteArray, contentType: String = ContentType.TEXT, mediaObjectId: UUID? = null): MessageDto {
        val sealed = peerCrypto.seal(plaintext, me, meKeys.public, peerKeys.private, peerKeys.public, peer)
        return server.store(peer, me, contentType, MessageCrypto.toWire(sealed), mediaObjectId)
    }
}
