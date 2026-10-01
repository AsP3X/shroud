package de.corespace.shroud.core.calls

import de.corespace.shroud.core.calls.crypto.CallCrypto
import de.corespace.shroud.core.contacts.Contacts
import de.corespace.shroud.core.contacts.ContactsHooks
import de.corespace.shroud.core.contacts.PeerIdentities
import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.hex
import de.corespace.shroud.core.crypto.hexToBytes
import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.ContactsListState
import de.corespace.shroud.core.model.PeerIdentityChange
import de.corespace.shroud.core.model.PeerIdentityChangedException
import de.corespace.shroud.core.model.PeerIdentityEvent
import de.corespace.shroud.core.net.BlockItemDto
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.Sealer
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.TempDirRule
import de.corespace.shroud.testing.XorSealer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Call secrets (plan C29, calls §11): the AFU-sealed store (iOS `CallSecretStore`,
 * `CallSecretStore.swift:36-100`) and the derivation that keeps them current
 * (`MessagingController.swift:4584-4637`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallSecretsTest {
    @get:Rule val temp = TempDirRule()

    private val alicePrivate = hexToBytes("ef80f1878a337c4c39eeb6578cf9af4c38bc681ed61ec43084d00d1e1657723d")
    private val alicePublic = hexToBytes("8b29cac884916cb7098ba9d90b61ef21d88596315cd1d04799a1962012e0b467")
    private val bobPublic = hexToBytes("389c6f5486c58d1c61ee17df7793440e1e5e4b7822cae03fc92c9d2a4920e338")
    private val expectedSecret = "39ea5a3a4128617d799fab4480c1bff13f92bef72ccb0bbc246da1504ba3839f"
    private val bob = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val carol = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    private val dave = UUID.fromString("00000000-0000-0000-0000-00000000000d")

    private class CountingSealer(private val inner: Sealer = XorSealer()) : Sealer {
        var seals = 0
        var refuse = false
        override fun seal(plaintext: ByteArray): ByteArray = inner.seal(plaintext).also { seals++ }
        override fun open(sealed: ByteArray): ByteArray = if (refuse) throw IllegalStateException("locked") else inner.open(sealed)
    }

    private fun store(sealer: Sealer = XorSealer(), seal: StorageSeal = StorageSeal(), deleted: () -> Unit = {}) =
        SealedCallSecretStore(SealedFile(temp.file("call-secrets.sealed"), sealer), seal, deleted)

    // ---- the sealed store ----

    @Test
    fun aSecretRoundTripsSealedAndSurvivesARestart() {
        val secret = ByteArray(32) { it.toByte() }
        store().save(bob, secret)
        val file = temp.file("call-secrets.sealed")
        assertTrue(file.exists())
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains(B64.encode(secret)))
        assertFalse(raw.contains(bob.toString()))
        assertArrayEquals(secret, store().secret(bob))
        assertNull(store().secret(carol))
    }

    @Test
    fun onlyThirtyTwoByteSecretsAndUnchangedSavesWriteNothing() {
        val sealer = CountingSealer()
        val store = store(sealer)
        store.save(bob, ByteArray(16))
        assertNull(store.secret(bob))
        assertEquals(0, sealer.seals)
        store.save(bob, ByteArray(32) { 1 })
        store.save(bob, ByteArray(32) { 1 })
        assertEquals(1, sealer.seals)
        store.save(carol, ByteArray(32) { 2 })
        assertEquals(2, sealer.seals)
    }

    @Test
    fun deleteAndDeleteAll() {
        var keyDeleted = 0
        val store = store(deleted = { keyDeleted++ })
        store.save(bob, ByteArray(32) { 1 })
        store.save(carol, ByteArray(32) { 2 })
        store.delete(bob)
        assertNull(store.secret(bob))
        assertArrayEquals(ByteArray(32) { 2 }, store.secret(carol))
        store.delete(carol)
        assertFalse(temp.file("call-secrets.sealed").exists())
        store.save(dave, ByteArray(32) { 3 })
        store.deleteAll()
        assertFalse(temp.file("call-secrets.sealed").exists())
        assertEquals(1, keyDeleted)
        assertNull(store.secret(dave))
    }

    @Test
    fun aWipeInProgressDropsEveryWrite() {
        val seal = StorageSeal()
        val store = store(seal = seal)
        store.save(bob, ByteArray(32) { 1 })
        seal.seal()
        store.save(carol, ByteArray(32) { 2 })
        store.delete(bob)
        seal.unseal()
        assertArrayEquals(ByteArray(32) { 1 }, store().secret(bob))
        assertNull(store().secret(carol))
    }

    @Test
    fun aRecordThePhoneRefusesIsNeverOverwritten() {
        store().save(bob, ByteArray(32) { 1 })
        val before = temp.file("call-secrets.sealed").readBytes()
        val sealer = CountingSealer().apply { refuse = true }
        val locked = store(sealer)
        assertNull(locked.secret(bob))
        locked.save(carol, ByteArray(32) { 2 })
        assertEquals(0, sealer.seals)
        assertArrayEquals(before, temp.file("call-secrets.sealed").readBytes())
    }

    // ---- derivation ----

    private class FakePeerIdentities(val keys: Map<UUID, ByteArray>) : PeerIdentities {
        val changes = MutableStateFlow<Map<UUID, PeerIdentityChange>>(emptyMap())
        val eventFlow = MutableSharedFlow<PeerIdentityEvent>(extraBufferCapacity = 8)
        var sendingError: Exception? = null
        override val identityChanges: StateFlow<Map<UUID, PeerIdentityChange>> = changes
        override val verifiedPeers: StateFlow<Set<UUID>> = MutableStateFlow(emptySet())
        override val events: SharedFlow<PeerIdentityEvent> = eventFlow
        override suspend fun resolvePublicKey(peer: UUID): ByteArray = keys[peer]?.copyOf() ?: throw IllegalStateException("no key")
        override suspend fun publicKeyForSending(peer: UUID): ByteArray {
            sendingError?.let { throw it }
            if (changes.value.containsKey(peer)) throw PeerIdentityChangedException()
            return resolvePublicKey(peer)
        }
        override suspend fun refresh(peer: UUID) = Unit
        override fun identityChange(peer: UUID): PeerIdentityChange? = changes.value[peer]
        override fun isSafetyVerified(peer: UUID) = false
        override fun confirmSafety(peer: UUID) = Unit
        override fun safetyNumber(peer: UUID): String? = null
        override fun acceptNewIdentity(peer: UUID) = Unit
        override fun clearMemory() = Unit
        override fun wipe() = Unit
    }

    private class FakeContacts(ids: List<UUID>) : Contacts {
        override val contacts = MutableStateFlow(ids.map { ContactItemDto(it, "user-$it", Instant.EPOCH) })
        override val incomingRequests: StateFlow<List<ContactRequestDto>> = MutableStateFlow(emptyList())
        override val listState: StateFlow<ContactsListState> = MutableStateFlow(ContactsListState())
        override val presence: StateFlow<Map<UUID, PresenceDto>> = MutableStateFlow(emptyMap())
        override val blocked: StateFlow<List<BlockItemDto>> = MutableStateFlow(emptyList())
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override val rosterChanges: SharedFlow<Unit> = changes
        override val pendingInvite = MutableStateFlow<String?>(null)
        override fun username(of: UUID): String? = contacts.value.firstOrNull { it.userId == of }?.username
        override suspend fun refresh(force: Boolean) = Unit
        override suspend fun refreshPresence(userIds: Collection<UUID>) = Unit
        override suspend fun add(invite: String): AddContactOutcome = AddContactOutcome.Failed("no")
        override suspend fun accept(request: ContactRequestDto): String? = null
        override suspend fun reject(request: ContactRequestDto): String? = null
        override suspend fun refreshBlocks() = Unit
        override suspend fun block(userId: UUID, username: String): String? = null
        override suspend fun unblock(userId: UUID): String? = null
        override fun bind(hooks: ContactsHooks) = Unit
        override fun hydrate(contacts: List<ContactItemDto>, requests: List<ContactRequestDto>) = Unit
        override fun start() = Unit
        override fun onForeground() = Unit
        override fun onBackground() = Unit
        override fun onConnectivityRegained() = Unit
        override fun stop(wipe: Boolean) = Unit
    }

    private fun secrets(
        store: CallSecretStore,
        peers: FakePeerIdentities?,
        contacts: FakeContacts? = null,
        unlocked: () -> Boolean = { true },
        io: kotlinx.coroutines.CoroutineDispatcher,
    ) = CallSecrets(
        store = store,
        isUnlocked = unlocked,
        secretWith = { peer -> if (unlocked()) CallCrypto.callSecret(alicePrivate, alicePublic, peer) else null },
        peers = { peers },
        contacts = { contacts },
        io = io,
    )

    @Test
    fun deriveTakesThePinnedKeyAndKeepsTheSecretForTheLockScreen() = runTest {
        val store = InMemoryCallSecretStore()
        val calls = secrets(store, FakePeerIdentities(mapOf(bob to bobPublic)), io = UnconfinedTestDispatcher(testScheduler))
        assertEquals(expectedSecret, calls.derive(bob).hex())
        assertEquals(expectedSecret, calls.secret(bob)!!.hex())
    }

    @Test
    fun deriveRefusesWhileLockedAndWhileAKeyChangeWaits() = runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val peers = FakePeerIdentities(mapOf(bob to bobPublic))
        suspend fun expectLocked(calls: CallSecrets) {
            try {
                calls.derive(bob)
                fail("derived while locked")
            } catch (e: CallSecretException) {
                assertEquals("Open Shroud and unlock your chats to connect this call.", e.message)
            }
        }
        expectLocked(secrets(InMemoryCallSecretStore(), peers, unlocked = { false }, io = io))
        expectLocked(secrets(InMemoryCallSecretStore(), null, io = io))
        peers.sendingError = CryptoError.Locked
        expectLocked(secrets(InMemoryCallSecretStore(), peers, io = io))
        peers.sendingError = null
        peers.changes.value = mapOf(bob to PeerIdentityChange(Bytes.of(bobPublic), Bytes.of(alicePublic)))
        val store = InMemoryCallSecretStore()
        try {
            secrets(store, peers, io = io).derive(bob)
            fail("derived during a key change")
        } catch (_: PeerIdentityChangedException) {
            assertNull(store.secret(bob))
        }
    }

    @Test
    fun refreshAllDerivesEveryContactAndDropsAChangedOne() = runTest {
        val store = InMemoryCallSecretStore()
        store.save(carol, ByteArray(32) { 9 })
        val peers = FakePeerIdentities(mapOf(bob to bobPublic, carol to bobPublic))
        peers.changes.value = mapOf(carol to PeerIdentityChange(Bytes.of(bobPublic), Bytes.of(alicePublic)))
        // dave has no key the phone can resolve now: skipped.
        val calls = secrets(store, peers, FakeContacts(listOf(bob, carol, dave)), io = UnconfinedTestDispatcher(testScheduler))
        calls.refreshAll()
        assertEquals(setOf(bob), store.peers)
        assertEquals(expectedSecret, store.secret(bob)!!.hex())
        // Locked: nothing happens.
        val locked = InMemoryCallSecretStore()
        secrets(locked, peers, FakeContacts(listOf(bob)), unlocked = { false }, io = UnconfinedTestDispatcher(testScheduler)).refreshAll()
        assertTrue(locked.peers.isEmpty())
    }

    @Test
    fun rosterChangesUnlocksAndKeyEventsKeepTheSecretsCurrent() = runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val store = InMemoryCallSecretStore()
        val peers = FakePeerIdentities(mapOf(bob to bobPublic, carol to bobPublic))
        val contacts = FakeContacts(listOf(bob))
        val unlockedFlow = MutableStateFlow(true)
        val calls = secrets(store, peers, contacts, unlocked = { unlockedFlow.value }, io = io)
        calls.start(backgroundScope, unlockedFlow)
        runCurrent()
        // The state at start is no unlock.
        assertTrue(store.peers.isEmpty())

        contacts.changes.emit(Unit)
        runCurrent()
        assertEquals(setOf(bob), store.peers)

        peers.eventFlow.emit(PeerIdentityEvent.KeyChanged(bob))
        runCurrent()
        assertTrue(store.peers.isEmpty())

        peers.eventFlow.emit(PeerIdentityEvent.KeyAccepted(carol))
        runCurrent()
        assertEquals(setOf(carol), store.peers)

        unlockedFlow.value = false
        runCurrent()
        contacts.contacts.value = listOf(ContactItemDto(bob, "bob", Instant.EPOCH), ContactItemDto(dave, "dave", Instant.EPOCH))
        unlockedFlow.value = true
        runCurrent()
        assertEquals(setOf(bob, carol), store.peers)
        assertSame(null, store.secret(dave))
    }
}
