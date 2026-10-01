package de.corespace.shroud.core.keys

import android.security.keystore.KeyProtection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.corespace.shroud.core.crypto.SystemEntropy
import de.corespace.shroud.core.storage.KeystoreSealer
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/**
 * The history-key vault on the real AndroidKeyStore (iOS `ios/shroudTests/HistoryKeyVaultTests.swift`;
 * crypto spec §10, §16.1). Needs a screen lock (PIN 1234). The round trips run with a test
 * [VaultKeyPolicy] that imports the wrap key **without** user authentication so they run
 * unattended; the production policy is exercised where no prompt is needed (store, probe, the
 * refusal to open without authentication) and end to end by `VaultFlowTest`. Test aliases
 * (`shroud.test.vault.wrap.*`, `shroud.test.vault.local`) and a cache-dir record, removed afterwards.
 */
@RunWith(AndroidJUnit4::class)
class HistoryKeyVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val recordFile = File(context.cacheDir, "vault-test/history-vault.v1")
    private val sealer = KeystoreSealer(LOCAL_ALIAS, unlockedDeviceRequired = true, isDeviceLocked = { DeviceLock.isLocked })
    private val historyKey = SystemEntropy.bytes(32)

    /** Imports without authentication, marked protected like production (`HistoryKeyVaultTests` runs unattended). */
    private val unattended = object : VaultKeyPolicy {
        override val protectionMarker: Int = 1

        override fun configure(builder: KeyProtection.Builder): KeyProtection.Builder = builder
    }

    /** Hands the cipher back without any prompt. */
    private val noPrompt = VaultAuthenticator { cipher, _ -> cipher }

    private fun vault(
        policy: VaultKeyPolicy = unattended,
        secure: () -> Boolean = { DeviceLock.isSecure },
        authenticator: VaultAuthenticator = noPrompt,
    ) = HistoryKeyVault(
        record = SealedFile(recordFile, sealer),
        keys = AndroidVaultKeyStore(policy = policy, aliasPrefix = WRAP_PREFIX),
        isDeviceSecure = secure,
        authenticator = authenticator,
        seal = StorageSeal(),
    )

    @Before
    fun setUp() {
        DeviceLock.ensureUnlocked()
        assumeTrue("the vault exists only behind a screen lock (adb shell locksettings set-pin 1234)", DeviceLock.isSecure)
        cleanUp()
    }

    @After
    fun tearDown() = cleanUp()

    private fun cleanUp() {
        recordFile.parentFile?.deleteRecursively()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.aliases().toList().filter { it.startsWith("shroud.test.vault.") }.forEach(ks::deleteEntry)
    }

    private fun wrapAliases(): List<String> =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList().filter { it.startsWith(WRAP_PREFIX) }

    /** `testNoPasscodeRefusesTheVault` (`:20-32`), with the screen lock reported missing. */
    @Test
    fun noScreenLockRefusesTheVault() = runBlocking<Unit> {
        val vault = vault(secure = { false })
        val user = UUID.randomUUID().toString()
        assertSame(VaultError.PasscodeNotSet, runCatching { vault.store(historyKey, user) }.exceptionOrNull())
        assertFalse(vault.hasBlob(user))
        assertSame(VaultError.PasscodeNotSet, runCatching { vault.unlock(user) }.exceptionOrNull())
        assertTrue(wrapAliases().isEmpty())
    }

    /** `testStoreMarksTheWrapKeyProtected` (`:34-37`) — with the production policy: storing never prompts. */
    @Test
    fun storeMarksTheWrapKeyProtected() {
        val vault = vault(policy = VaultKeyPolicy.UserPresence)
        val user = UUID.randomUUID().toString()
        vault.store(historyKey, user)
        assertTrue(vault.isWrapKeyProtected)
        assertTrue(vault.hasBlob(user))
        assertNotNull(vault.keySecurity())
        // The probe initialises the cipher without authentication (crypto §10.3).
        assertEquals(VaultState.Ready, vault.state(user))
        assertEquals(1, wrapAliases().size)
    }

    /** `testStoreAndUnlockRoundTrip` (`:39-49`). */
    @Test
    fun storeAndUnlockRoundTrip() = runBlocking<Unit> {
        val vault = vault()
        val user = UUID.randomUUID().toString()
        vault.store(historyKey, user)
        assertTrue(vault.hasBlob(user))
        assertArrayEquals(historyKey, vault.unlock(user))
        // Every store rotates the wrap key and deletes the previous one.
        val first = wrapAliases().single()
        vault.store(historyKey, user)
        val second = wrapAliases().single()
        assertFalse(first == second)
        assertArrayEquals(historyKey, vault.unlock(user.uppercase()))
    }

    /** `testWrongUserFails` (`:51-57`). */
    @Test
    fun wrongUserFails() = runBlocking<Unit> {
        val vault = vault()
        vault.store(historyKey, UUID.randomUUID().toString())
        assertSame(VaultError.NotFound, runCatching { vault.unlock(UUID.randomUUID().toString()) }.exceptionOrNull())
    }

    /** `testClearRemovesBlob` (`:59-65`). */
    @Test
    fun clearRemovesBlob() = runBlocking<Unit> {
        val vault = vault()
        val user = UUID.randomUUID().toString()
        vault.store(historyKey, user)
        vault.clear()
        assertFalse(vault.hasBlob(user))
        assertTrue(wrapAliases().isEmpty())
        assertTrue(runCatching { vault.unlock(user) }.isFailure)
    }

    /** The production wrap key does not open without the user: no prompt, no history key. */
    @Test
    fun theProductionWrapKeyRefusesWithoutAuthentication() = runBlocking<Unit> {
        val vault = vault(policy = VaultKeyPolicy.UserPresence)
        val user = UUID.randomUUID().toString()
        vault.store(historyKey, user)
        val error = runCatching { vault.unlock(user) }.exceptionOrNull()
        assertTrue("got $error", error is VaultError.Keystore)
        assertTrue("a refused open never clears the vault", vault.hasBlob(user))
    }

    /** A vanished alias is a dead vault (crypto §10.3): the probe says so and the unlock clears it. */
    @Test
    fun aVanishedWrapKeyIsKeyInvalidated() = runBlocking<Unit> {
        val vault = vault()
        val user = UUID.randomUUID().toString()
        vault.store(historyKey, user)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(wrapAliases().single())
        assertEquals(VaultState.KeyInvalidated, vault.state(user))
        assertSame(VaultError.KeyInvalidated, runCatching { vault.unlock(user) }.exceptionOrNull())
        assertFalse(vault.hasBlob(user))
        assertEquals(VaultState.NotFound, vault.state(user))
    }

    private companion object {
        const val WRAP_PREFIX = "shroud.test.vault.wrap."
        const val LOCAL_ALIAS = "shroud.test.vault.local"
    }
}
