package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.CryptoError
import de.corespace.shroud.core.crypto.PeerLocks
import de.corespace.shroud.core.keys.PeerIdentityStore
import de.corespace.shroud.core.model.Bytes
import de.corespace.shroud.core.model.PeerIdentityChange
import de.corespace.shroud.core.model.PeerIdentityChangedException
import de.corespace.shroud.core.model.PeerIdentityEvent
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.storage.ScriptedSealer
import de.corespace.shroud.core.storage.SealResult
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.MainDispatcherRule
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import java.util.UUID

/**
 * `PeerIdentityController` (iOS `MessagingController.swift:4508-4652`; contacts §4.8, §9
 * *PeerIdentityControllerTest*) with the pin semantics of the web's
 * `web/src/crypto/peerIdentity.selftest.ts`: the first key sticks and does not block, the same key
 * changes nothing, a different key waits with the pin and its verified flag kept, accepting starts
 * the comparison over, comparing marks it. Plus the Android rule that an unreadable pin trusts
 * nothing (plan §1.7.4 note).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerIdentityControllerTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    @get:Rule
    val temp = TempDirRule()

    private val peer = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
    private val first = ByteArray(32) { 0x07 }
    private val next = ByteArray(32) { 0x08 }

    /** contacts §4.9 goldens (`IdentitySafetyNumberTests.matchesTheSharedGoldens`). */
    private val a = ByteArray(32) { (0x01 + it).toByte() }
    private val b = ByteArray(32) { (0x21 + it).toByte() }

    private val sealer = ScriptedSealer()
    private val file get() = temp.noBackupFilesDir.resolve("keys/peer-identity.v1")
    private val store by lazy { PeerIdentityStore(SealedFile(file, sealer), StorageSeal()) }
    private val backend = FakeContactsBackend()
    private val ratchets = RecordingRatchets()
    private var token: String? = "tok"
    private var localKey: ByteArray? = a

    private fun TestScope.controller(on: PeerIdentityStore = store) = PeerIdentityController(
        backend = backend,
        store = on,
        ratchets = ratchets,
        peerLocks = PeerLocks(),
        token = { token },
        localIdentityPublicKey = { localKey?.copyOf() },
        scope = backgroundScope,
        io = main.dispatcher,
    )

    private fun TestScope.recordEvents(controller: PeerIdentityController): MutableList<PeerIdentityEvent> {
        val seen = mutableListOf<PeerIdentityEvent>()
        backgroundScope.launch { controller.events.collect { seen += it } }
        runCurrent()
        return seen
    }

    private fun serves(key: ByteArray) {
        backend.onIdentityKey = { B64.encode(key) }
    }

    @Test
    fun theFirstKeyIsPinnedAndDoesNotBlock() = runTest(main.dispatcher) {
        val identities = controller()
        val events = recordEvents(identities)
        serves(first)
        assertArrayEquals(first, identities.resolvePublicKey(peer))
        runCurrent()
        assertArrayEquals(first, store.publicKey(peer))
        assertFalse(store.isVerified(peer))
        assertEquals(listOf<PeerIdentityEvent>(PeerIdentityEvent.Pinned(peer)), events)
        assertNull(identities.identityChange(peer))
        assertArrayEquals(first, identities.publicKeyForSending(peer))
    }

    /** `PeerIdentityStoreTests.roundTripAndClear` through the controller: a pin outlives the process. */
    @Test
    fun aPinAndItsVerifiedFlagSurviveARestart() = runTest(main.dispatcher) {
        serves(first)
        val identities = controller()
        identities.resolvePublicKey(peer)
        identities.confirmSafety(peer)
        val reopened = PeerIdentityStore(SealedFile(file, sealer), StorageSeal())
        val restarted = controller(on = reopened)
        serves(next)
        assertTrue(restarted.isSafetyVerified(peer))
        assertArrayEquals(first, restarted.resolvePublicKey(peer))
        runCurrent()
        assertEquals(PeerIdentityChange(Bytes.of(first), Bytes.of(next)), restarted.identityChange(peer))
        restarted.wipe()
        assertNull(PeerIdentityStore(SealedFile(file, sealer), StorageSeal()).publicKey(peer))
    }

    @Test
    fun theSameKeyChangesNothing() = runTest(main.dispatcher) {
        store.save(peer, first)
        serves(first)
        val identities = controller()
        val events = recordEvents(identities)
        assertArrayEquals(first, identities.publicKeyForSending(peer))
        identities.refresh(peer)
        runCurrent()
        assertTrue(identities.identityChanges.value.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun aNewKeyWaitsAndThePinAndItsVerifiedFlagStay() = runTest(main.dispatcher) {
        store.save(peer, first)
        store.setVerified(peer, true)
        serves(next)
        val identities = controller()
        val events = recordEvents(identities)
        try {
            identities.publicKeyForSending(peer)
            fail("a waiting key must block sends")
        } catch (_: PeerIdentityChangedException) {
        }
        val change = PeerIdentityChange(previousKey = Bytes.of(first), currentKey = Bytes.of(next))
        assertEquals(change, identities.identityChange(peer))
        assertArrayEquals(first, store.publicKey(peer))
        assertTrue(identities.isSafetyVerified(peer))
        // The decrypt path keeps using the pin.
        assertArrayEquals(first, identities.resolvePublicKey(peer))
        // Seen again: no second event.
        identities.refresh(peer)
        runCurrent()
        assertEquals(listOf<PeerIdentityEvent>(PeerIdentityEvent.KeyChanged(peer)), events)
        assertEquals(
            "This contact's encryption key changed. Verify their safety number before sending.",
            PeerIdentityChangedException().message,
        )
    }

    @Test
    fun acceptingRepinsClearsVerificationAndDropsTheRatchetSession() = runTest(main.dispatcher) {
        store.save(peer, first)
        store.setVerified(peer, true)
        ratchets.save(peer, byteArrayOf(1, 2, 3))
        serves(next)
        val identities = controller()
        identities.confirmSafety(peer)
        val events = recordEvents(identities)
        identities.refresh(peer)
        assertTrue(peer in identities.verifiedPeers.value)
        identities.acceptNewIdentity(peer)
        runCurrent()
        assertArrayEquals(next, store.publicKey(peer))
        assertFalse(store.isVerified(peer))
        assertFalse(identities.isSafetyVerified(peer))
        assertFalse(peer in identities.verifiedPeers.value)
        assertNull(identities.identityChange(peer))
        assertNull(ratchets.load(peer))
        // Once at once, once more under the peer's lock.
        assertEquals(listOf(peer, peer), ratchets.deletes)
        assertEquals(listOf(PeerIdentityEvent.KeyChanged(peer), PeerIdentityEvent.KeyAccepted(peer)), events)
        assertArrayEquals(next, identities.publicKeyForSending(peer))
        // Comparing marks it.
        identities.confirmSafety(peer)
        assertTrue(store.isVerified(peer))
        assertTrue(identities.isSafetyVerified(peer))
    }

    @Test
    fun acceptingWithoutAChangeDoesNothing() = runTest(main.dispatcher) {
        store.save(peer, first)
        val identities = controller()
        identities.acceptNewIdentity(peer)
        runCurrent()
        assertArrayEquals(first, store.publicKey(peer))
        assertTrue(ratchets.deletes.isEmpty())
    }

    @Test
    fun confirmingWithoutAPinIsANoOp() = runTest(main.dispatcher) {
        val identities = controller()
        identities.confirmSafety(peer)
        assertFalse(identities.isSafetyVerified(peer))
        assertTrue(identities.verifiedPeers.value.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun anUnreachableServerInventsNothing() = runTest(main.dispatcher) {
        store.save(peer, first)
        backend.onIdentityKey = { throw ApiError.Transport("The Internet connection appears to be offline.") }
        val identities = controller()
        assertArrayEquals(first, identities.publicKeyForSending(peer))
        identities.refresh(peer)
        assertNull(identities.identityChange(peer))
    }

    @Test
    fun aFirstUseFetchFailureThrowsAndPinsNothing() = runTest(main.dispatcher) {
        backend.onIdentityKey = { throw ApiError.Transport("offline") }
        val identities = controller()
        try {
            identities.resolvePublicKey(peer)
            fail("nothing to decrypt with")
        } catch (_: ApiError.Transport) {
        }
        backend.onIdentityKey = { "not base64!" }
        try {
            identities.resolvePublicKey(peer)
            fail("a key that is not Base64 is a decoding error")
        } catch (_: ApiError.Decoding) {
        }
        assertNull(store.publicKey(peer))
    }

    @Test
    fun aPinIsRecheckedOncePerSessionInTheBackground() = runTest(main.dispatcher) {
        store.save(peer, first)
        serves(first)
        val identities = controller()
        identities.resolvePublicKey(peer)
        identities.resolvePublicKey(peer)
        runCurrent()
        assertEquals(1, backend.count("identity"))
        identities.resolvePublicKey(peer)
        runCurrent()
        assertEquals(1, backend.count("identity"))
        // A new session (chats locked and unlocked) checks again.
        identities.clearMemory()
        identities.resolvePublicKey(peer)
        runCurrent()
        assertEquals(2, backend.count("identity"))
        // Sends always check.
        identities.publicKeyForSending(peer)
        assertEquals(3, backend.count("identity"))
    }

    @Test
    fun theBackgroundRecheckRecordsAChange() = runTest(main.dispatcher) {
        store.save(peer, first)
        serves(next)
        val identities = controller()
        assertArrayEquals(first, identities.resolvePublicKey(peer))
        runCurrent()
        assertEquals(PeerIdentityChange(Bytes.of(first), Bytes.of(next)), identities.identityChange(peer))
    }

    /** Android contract: a pin the phone cannot read now is never "not pinned" (plan §1.7.4 note). */
    @Test
    fun anUnreadablePinTrustsNothing() = runTest(main.dispatcher) {
        store.save(peer, first)
        sealer.readFailure = SealResult.DeviceLocked
        val locked = PeerIdentityStore(SealedFile(file, sealer), StorageSeal())
        serves(next)
        val identities = controller(on = locked)
        for (call in listOf<suspend () -> ByteArray>({ identities.resolvePublicKey(peer) }, { identities.publicKeyForSending(peer) })) {
            try {
                call()
                fail("an unreadable pin must not let a server key through")
            } catch (e: CryptoError) {
                assertSame(CryptoError.Locked, e)
            }
        }
        // Nothing was decided or written: no change, no new pin.
        assertNull(identities.identityChange(peer))
        assertFalse(identities.isSafetyVerified(peer))
        assertNull(identities.safetyNumber(peer))
        identities.refresh(peer)
        sealer.readFailure = null
        assertArrayEquals(first, PeerIdentityStore(SealedFile(file, sealer), StorageSeal()).publicKey(peer))
    }

    @Test
    fun signedOutWithNothingPinnedIsLocked() = runTest(main.dispatcher) {
        token = null
        val identities = controller()
        try {
            identities.resolvePublicKey(peer)
            fail("no session, no key")
        } catch (e: CryptoError) {
            assertSame(CryptoError.Locked, e)
        }
        assertEquals(0, backend.count("identity"))
        identities.refresh(peer)
        assertEquals(0, backend.count("identity"))
    }

    /** P10b (`IdentitySafetyNumberTests.aPendingKeyChangeShowsTheNewKeysNumber`, `matchesTheSharedGoldens`). */
    @Test
    fun theSafetyNumberIsThePendingKeysWhileAChangeWaits() = runTest(main.dispatcher) {
        store.save(peer, a)
        serves(b)
        val identities = controller()
        assertEquals("29696 81578 32876 91411 15478 21467 89245 24174 87371 59194 49089 78176", identities.safetyNumber(peer))
        identities.refresh(peer)
        assertEquals("39936 00420 36095 80875 11472 14538 50394 55836 81423 99087 38599 17095", identities.safetyNumber(peer))
        localKey = null
        assertNull(identities.safetyNumber(peer))
        assertNull(controller().safetyNumber(UUID.randomUUID()))
    }

    @Test
    fun theSafetyNumberKeyIsAPendingChangesNewKeyElseThePin() {
        val pinned = ByteArray(32) { 0x07 }
        val fresh = ByteArray(32) { 0x08 }
        val change = PeerIdentityChange(previousKey = Bytes.of(pinned), currentKey = Bytes.of(fresh))
        assertArrayEquals(fresh, PeerIdentityController.safetyNumberKey(pinned = pinned, change = change))
        assertArrayEquals(pinned, PeerIdentityController.safetyNumberKey(pinned = pinned, change = null))
        assertNull(PeerIdentityController.safetyNumberKey(pinned = null, change = null))
    }

    @Test
    fun clearingMemoryKeepsThePinsAndWipingDeletesThem() = runTest(main.dispatcher) {
        store.save(peer, first)
        serves(next)
        val identities = controller()
        identities.refresh(peer)
        identities.confirmSafety(peer)
        identities.onContactsStopped(wipe = false)
        assertTrue(identities.identityChanges.value.isEmpty())
        assertTrue(identities.verifiedPeers.value.isEmpty())
        assertArrayEquals(first, store.publicKey(peer))
        identities.onContactsStopped(wipe = true)
        assertNull(store.publicKey(peer))
        assertFalse(file.exists())
    }

    @Test
    fun aCheckThatOutlivesTheSessionRecordsNothing() = runTest(main.dispatcher) {
        store.save(peer, first)
        val answer = CompletableDeferred<String>()
        backend.onIdentityKey = { answer.await() }
        val identities = controller()
        val check = launch { identities.refresh(peer) }
        runCurrent()
        identities.clearMemory()
        answer.complete(B64.encode(next))
        check.join()
        assertNull(identities.identityChange(peer))
    }

    @Test
    fun aFirstUseThatOutlivesAWipePinsNothing() = runTest(main.dispatcher) {
        val answer = CompletableDeferred<String>()
        backend.onIdentityKey = { answer.await() }
        val identities = controller()
        var failure: Throwable? = null
        val decrypt = launch {
            try {
                identities.resolvePublicKey(peer)
            } catch (e: CryptoError) {
                failure = e
            }
        }
        runCurrent()
        identities.wipe()
        answer.complete(B64.encode(first))
        decrypt.join()
        assertSame(CryptoError.Locked, failure)
        assertNull(store.publicKey(peer))
        assertFalse(file.exists())
    }

    @Test
    fun twoFirstUsesPinOneKey() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var served = 0
        backend.onIdentityKey = {
            gate.await()
            B64.encode(if (served++ == 0) first else next)
        }
        val identities = controller()
        val results = mutableListOf<ByteArray>()
        val one = launch { results += identities.resolvePublicKey(peer) }
        val two = launch { results += identities.resolvePublicKey(peer) }
        runCurrent()
        gate.complete(Unit)
        one.join()
        two.join()
        assertArrayEquals(first, store.publicKey(peer))
        results.forEach { assertArrayEquals(first, it) }
        // The second, different key is a change like any other.
        assertEquals(PeerIdentityChange(Bytes.of(first), Bytes.of(next)), identities.identityChange(peer))
    }
}
