package de.corespace.shroud.core.messaging.local

import de.corespace.shroud.core.keys.LocalNames
import de.corespace.shroud.core.keys.SealedLocalState
import de.corespace.shroud.core.media.LocalMediaStore
import de.corespace.shroud.core.media.SealedMediaReader
import de.corespace.shroud.core.media.SealedMediaWriter
import de.corespace.shroud.core.messaging.CachedConversation
import de.corespace.shroud.core.messaging.MessagingSnapshot
import de.corespace.shroud.core.messaging.RosterSnapshot
import de.corespace.shroud.core.model.ChatMessage
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.FakeAppClock
import de.corespace.shroud.testing.SealedTestKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One account's sealed store under a temp `noBackupFilesDir`, shared by the repository tests. The
 * key is [SealedTestKey] (`0x5A × 32`); [unlock] goes through `SealedLocalState.unlock`, so the
 * repositories hear it like a real chat unlock. By default queued writes run at once
 * ([Dispatchers.Unconfined]), so a test reads the files right after a save, as iOS writes them
 * synchronously; [pausedWriter] keeps them queued until a flush.
 */
class StoreFixture(val root: File, val zone: ZoneId = ZoneId.of("Europe/Berlin")) {
    val userId: UUID = UUID.randomUUID()
    val clock = FakeAppClock()
    val state = SealedLocalState().also { it.unlock(SealedTestKey.bytes()) }
    val storageSeal = StorageSeal()
    val media = FakeLocalMediaStore()

    /** The names the store uses under the test key. */
    val names: LocalNames get() = LocalNames.derive(SealedTestKey.bytes())

    fun unlock(key: ByteArray = SealedTestKey.bytes()) = state.unlock(key)

    fun lock() = state.lock()

    fun repository(writerScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)): MessagingLocalRepository =
        MessagingLocalRepository(
            root = root,
            state = state,
            storageSeal = storageSeal,
            clock = clock,
            localMedia = { media },
            zone = { zone },
            writerScope = writerScope,
            flushDispatcher = Dispatchers.Unconfined,
        )

    /** A repository whose writer never runs on its own: writes wait for flush, a lock or a read. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun pausedWriter(): MessagingLocalRepository = repository(CoroutineScope(StandardTestDispatcher()))

    fun store(state: SealedLocalState = this.state): LocalMessageStore =
        LocalMessageStore(root, LocalStoreKeys(state), clock, storageSeal) { zone }

    // ---- Paths under the test key ----

    val shroudDir: File get() = File(root, "shroud")
    val userDir: File get() = File(root, "shroud/messages/" + names.name(LocalNames.Kind.USER, userId))
    val rosterFile: File get() = File(userDir, "roster.sealed")
    val reactionsFile: File get() = File(userDir, "reactions.sealed")
    val annotationsFile: File get() = File(userDir, "annotations.sealed")

    fun threadFile(peer: UUID): File = File(userDir, "threads/" + names.name(LocalNames.Kind.THREAD, peer) + ".sealed")

    fun plaintextFile(id: UUID): File = File(root, "shroud/plaintext/" + names.name(LocalNames.Kind.MESSAGE, id) + ".sealed")

    // ---- Messages ----

    val now: Instant get() = clock.now()

    fun message(peer: UUID, text: String, createdAt: Instant = now, ids: MutableList<UUID>? = null): ChatMessage {
        val id = UUID.randomUUID()
        ids?.add(id)
        return ChatMessage(id = id, peerUserId = peer, senderUserId = peer, text = text, createdAt = createdAt, isMine = false, deleted = false)
    }

    fun emptyRoster(unread: Map<UUID, Int> = emptyMap(), conversations: List<CachedConversation> = emptyList()) =
        RosterSnapshot(conversations, emptyList(), emptyList(), unread)

    fun persist(repository: MessagingLocalRepository, threads: Map<UUID, List<ChatMessage>>, roster: RosterSnapshot = emptyRoster()): Set<UUID> =
        repository.persist(userId, MessagingSnapshot(roster, threads))

    fun persistThread(repository: MessagingLocalRepository, peer: UUID, messages: List<ChatMessage>, roster: RosterSnapshot = emptyRoster()): Set<UUID> =
        repository.persistThread(userId, peer, messages, roster)

    fun flush(repository: MessagingLocalRepository) = runBlocking { repository.flush() }

    /** iOS backdates files to 1 000 000 s after 1970 (`MessagingLocalPersistTests.swift:15`). */
    fun backdate(vararg files: File) = files.forEach { check(it.setLastModified(OLD_MILLIS)) { "could not backdate ${it.name}" } }

    companion object {
        const val OLD_MILLIS = 1_000_000_000L
    }
}

/** An in-memory `LocalMediaStore` (W2-MEDIA-STORE's seam): presence, save, removal and clear. */
class FakeLocalMediaStore : LocalMediaStore {
    val files = ConcurrentHashMap<UUID, ByteArray>()
    var clearedAll = 0

    override suspend fun readAll(messageId: UUID): ByteArray? = files[messageId]?.copyOf()
    override fun has(messageId: UUID): Boolean = files.containsKey(messageId)
    override suspend fun save(messageId: UUID, data: ByteArray) {
        files[messageId] = data.copyOf()
    }

    fun put(messageId: UUID, data: ByteArray) {
        files[messageId] = data.copyOf()
    }

    override fun writer(messageId: UUID): SealedMediaWriter = throw UnsupportedOperationException()
    override fun openReader(messageId: UUID): SealedMediaReader? = null
    override fun rename(from: UUID, to: UUID) {
        files.remove(from)?.let { files[to] = it }
    }

    override fun remove(messageIds: Collection<UUID>) {
        messageIds.forEach { files.remove(it) }
    }

    override fun clearAll() {
        files.clear()
        clearedAll++
    }

    override fun inventory(): Pair<Int, Long> = files.size to files.values.sumOf { it.size.toLong() }
}
