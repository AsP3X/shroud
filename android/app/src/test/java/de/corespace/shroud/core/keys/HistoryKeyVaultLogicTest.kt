package de.corespace.shroud.core.keys

import de.corespace.shroud.core.crypto.B64
import de.corespace.shroud.core.crypto.ScriptedEntropy
import de.corespace.shroud.core.crypto.hex
import de.corespace.shroud.core.keys.FakeVaultAuthenticator.Step
import de.corespace.shroud.core.storage.ScriptedSealer
import de.corespace.shroud.core.storage.SealResult
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.TempDirRule
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The vault's logic on the JVM (crypto spec §10, §16.1): iOS `HistoryKeyVaultTests` cases on a
 * software key store, the record format, alias rotation, `KeyInvalidated`, the `BiometricPrompt`
 * error mapping (`HistoryKeyVault.swift:267-316`), the one passcode retry with a fresh cipher and
 * the 90 s limit (`:45-48`). The AndroidKeyStore itself is covered by the instrumented
 * `HistoryKeyVaultTest`.
 */
class HistoryKeyVaultLogicTest {
    @get:Rule
    val temp = TempDirRule()

    private val sealer = ScriptedSealer()
    private val keys = SoftwareVaultKeyStore()
    private val seal = StorageSeal()
    private var secure = true
    private val user = "8f14e45f-ceea-467a-9575-3a6b7a1e6c0e"
    private val historyKey = ByteArray(32) { (it + 1).toByte() }
    private val recordFile get() = temp.noBackupFilesDir.resolve("keys/history-vault.v1")

    private fun TestScope.vault(authenticator: VaultAuthenticator = FakeVaultAuthenticator()) =
        HistoryKeyVault(
            record = SealedFile(recordFile, sealer),
            keys = keys,
            isDeviceSecure = { secure },
            authenticator = authenticator,
            seal = seal,
            io = StandardTestDispatcher(testScheduler),
        )

    private fun record(): JsonObject {
        val opened = sealer.open(recordFile.readBytes())
        return Json.parseToJsonElement(opened.decodeToString()).jsonObject
    }

    // ---- iOS HistoryKeyVaultTests (`ios/shroudTests/HistoryKeyVaultTests.swift`) ----

    /** `testNoPasscodeRefusesTheVault` (`:20-32`). */
    @Test
    fun noScreenLockRefusesTheVault() = runTest {
        secure = false
        val vault = vault()
        assertThrowsVault(VaultError.PasscodeNotSet) { vault.store(historyKey, user) }
        assertFalse(vault.hasBlob(user))
        assertThrowsVault(VaultError.PasscodeNotSet) { vault.unlock(user) }
        assertEquals(VaultState.NoScreenLock, vault.state(user))
        assertFalse(vault.canProtectWrapKey)
        assertTrue(keys.aliases().isEmpty())
    }

    /** `testStoreMarksTheWrapKeyProtected` (`:34-37`). */
    @Test
    fun storeMarksTheWrapKeyProtected() = runTest {
        val vault = vault()
        assertFalse(vault.isWrapKeyProtected)
        vault.store(historyKey, user)
        assertTrue(vault.isWrapKeyProtected)
    }

    /** `testStoreAndUnlockRoundTrip` (`:39-49`). */
    @Test
    fun storeAndUnlockRoundTrip() = runTest {
        val vault = vault()
        vault.store(historyKey, user)
        assertTrue(vault.hasBlob(user))
        assertEquals(VaultState.Ready, vault.state(user))
        assertArrayEquals(historyKey, vault.unlock(user))
    }

    /** `testWrongUserFails` (`:51-57`). */
    @Test
    fun wrongUserFails() = runTest {
        val vault = vault()
        vault.store(historyKey, user)
        val other = "11111111-2222-4333-8444-555555555555"
        assertThrowsVault(VaultError.NotFound) { vault.unlock(other) }
        assertFalse(vault.hasBlob(other))
        assertEquals(VaultState.NotFound, vault.state(other))
    }

    /** `testClearRemovesBlob` (`:59-65`). */
    @Test
    fun clearRemovesBlobAndEveryWrapKey() = runTest {
        val vault = vault()
        vault.store(historyKey, user)
        vault.clear()
        assertFalse(vault.hasBlob(user))
        assertFalse(recordFile.exists())
        assertTrue(keys.aliases().isEmpty())
        assertThrowsVault(VaultError.NotFound) { vault.unlock(user) }
    }

    // ---- Android record and rotation (crypto §10.2, §10.5) ----

    @Test
    fun theRecordHoldsTheBlobNeverTheKey() = runTest {
        val vault = HistoryKeyVault(
            record = SealedFile(recordFile, sealer),
            keys = keys,
            isDeviceSecure = { secure },
            authenticator = FakeVaultAuthenticator(),
            seal = seal,
            entropy = ScriptedEntropy(ByteArray(32) { 0x42 }, ByteArray(12) { 0x24 }),
            io = StandardTestDispatcher(testScheduler),
        )
        vault.store(historyKey, user.uppercase())
        val r = record()
        assertEquals(setOf("v", "user_id", "alias", "blob", "protection", "security"), r.keys)
        assertEquals(1, r["v"]!!.jsonPrimitive.int)
        assertEquals(user.uppercase(), r["user_id"]!!.jsonPrimitive.content)
        assertEquals(keys.aliases().single(), r["alias"]!!.jsonPrimitive.content)
        assertEquals(1, r["protection"]!!.jsonPrimitive.int)
        assertEquals("tee", r["security"]!!.jsonPrimitive.content)
        val blob = B64.decodeStrict(r["blob"]!!.jsonPrimitive.content)!!
        assertEquals(HistoryKeyVault.BLOB_BYTES, blob.size)
        assertEquals("242424242424242424242424", blob.copyOfRange(0, 12).hex())
        assertFalse("the history key is not in the clear", blob.hex().contains(historyKey.hex()))
        // The blob opens with the wrap key and the account AAD only (case-insensitive id).
        assertArrayEquals(historyKey, vault.unlock(user))
    }

    @Test
    fun everyStoreRotatesTheWrapKeyAndDeletesTheOldOne() = runTest {
        val vault = vault()
        vault.store(historyKey, user)
        val first = keys.aliases().single()
        vault.store(historyKey, user)
        val second = keys.aliases().single()
        assertTrue(first != second)
        assertEquals(second, record()["alias"]!!.jsonPrimitive.content)
        assertArrayEquals(historyKey, vault.unlock(user))
        assertEquals(VaultKeyStore.Security.Tee, vault.keySecurity())
    }

    @Test
    fun aSoftwareOnlyKeystoreIsAllowedAndRecorded() = runTest {
        keys.security = VaultKeyStore.Security.Software
        val vault = vault()
        vault.store(historyKey, user)
        assertEquals(VaultKeyStore.Security.Software, vault.keySecurity())
        assertEquals("software", record()["security"]!!.jsonPrimitive.content)
    }

    @Test
    fun aWipeInProgressDropsTheStore() = runTest {
        val vault = vault()
        seal.seal()
        vault.store(historyKey, user)
        assertFalse(recordFile.exists())
        assertTrue(keys.aliases().isEmpty())
    }

    @Test
    fun anImportFailureKeepsThePreviousVault() = runTest {
        val vault = vault()
        vault.store(historyKey, user)
        val alias = keys.aliases().single()
        keys.importFailure = IllegalStateException("keystore says no")
        val error = runCatching { vault.store(ByteArray(32) { 9 }, user) }.exceptionOrNull()
        assertTrue(error.toString(), error is VaultError.Keystore)
        assertEquals(listOf(alias), keys.aliases())
        assertArrayEquals(historyKey, vault.unlock(user))
    }

    /** No unprotected fallback, ever: the lock can vanish between the check and the import (`HistoryKeyVault.swift:486-507`). */
    @Test
    fun aScreenLockRemovedDuringTheImportIsPasscodeNotSetAndLeavesNoKey() = runTest {
        val vault = vault()
        // The import succeeds, but the lock is gone by then: the new key is deleted again.
        keys.onImport = { secure = false }
        assertThrowsVault(VaultError.PasscodeNotSet) { vault.store(historyKey, user) }
        assertTrue(keys.aliases().isEmpty())
        assertFalse(recordFile.exists())
        // The import fails because the lock vanished.
        secure = true
        keys.importFailure = IllegalStateException("Secure lock screen must be enabled")
        assertThrowsVault(VaultError.PasscodeNotSet) { vault.store(historyKey, user) }
        // The import fails with the lock still set: a Keystore error.
        secure = true
        keys.onImport = {}
        assertThrowsVault(VaultError.Keystore::class.java) { vault.store(historyKey, user) }
        assertTrue(keys.aliases().isEmpty())
    }

    @Test
    fun aRecordWriteThatFailsDeletesTheNewKey() = runTest {
        val vault = vault()
        sealer.sealFails = true
        assertThrowsVault(VaultError.Keystore::class.java) { vault.store(historyKey, user) }
        assertTrue(keys.aliases().isEmpty())
    }

    @Test
    fun aDamagedBlobIsNotFound() = runTest {
        val vault = vault()
        vault.store(historyKey, user)
        val r = record().toMutableMap()
        r["blob"] = JsonPrimitive(B64.encode(ByteArray(59)))
        SealedFile(recordFile, sealer).write(JsonObject(r).toString().toByteArray())
        assertFalse(vault.hasBlob(user))
        assertThrowsVault(VaultError.NotFound) { vault.unlock(user) }
    }

    @Test
    fun theBlobIsBoundToItsAccount() = runTest {
        val vault = vault()
        vault.store(historyKey, user)
        // Move the record to another account id: the AAD no longer matches, so it does not open.
        val other = "11111111-2222-4333-8444-555555555555"
        val r = record().toMutableMap()
        r["user_id"] = JsonPrimitive(other)
        SealedFile(recordFile, sealer).write(JsonObject(r).toString().toByteArray())
        assertThrowsVault(VaultError.OpenFailed) { vault.unlock(other) }
    }

    @Test
    fun aRecordLockedByThePhoneReadsAsNoVault() = runTest {
        val vault = vault()
        vault.store(historyKey, user)
        sealer.readFailure = SealResult.DeviceLocked
        assertFalse(vault.hasBlob(user))
        assertEquals(VaultState.NotFound, vault.state(user))
    }

    // ---- KeyInvalidated (crypto §10.3 steps 3–4) ----

    @Test
    fun anInvalidatedWrapKeyClearsTheVaultAndAsksForThePhrase() = runTest {
        val auth = FakeVaultAuthenticator()
        val vault = vault(auth)
        vault.store(historyKey, user)
        keys.invalidate(keys.aliases().single())
        // The probe sees it before any tap, without prompting or clearing.
        assertEquals(VaultState.KeyInvalidated, vault.state(user))
        assertTrue(vault.hasBlob(user))
        assertThrowsVault(VaultError.KeyInvalidated) { vault.unlock(user) }
        assertTrue("no prompt for a dead key", auth.prompts.isEmpty())
        assertFalse(vault.hasBlob(user))
        assertTrue(keys.aliases().isEmpty())
        assertEquals(VaultState.NotFound, vault.state(user))
    }

    @Test
    fun aVanishedAliasIsKeyInvalidatedToo() = runTest {
        val vault = vault()
        vault.store(historyKey, user)
        keys.delete(keys.aliases().single())
        assertEquals(VaultState.KeyInvalidated, vault.state(user))
        assertThrowsVault(VaultError.KeyInvalidated) { vault.unlock(user) }
        assertFalse(recordFile.exists())
    }

    // ---- Prompt error mapping (crypto §10.4 table) ----

    @Test
    fun promptErrorCodesMapAsSpecified() {
        fun outcome(code: Int, method: UnlockMethod) = HistoryKeyVault.classifyPromptError(code, method)
        fun fail(code: Int, method: UnlockMethod) = (outcome(code, method) as HistoryKeyVault.PromptOutcome.Fail).error
        for (method in UnlockMethod.entries) {
            assertSame(VaultError.UserCancelled, fail(10, method)) // ERROR_USER_CANCELED
            assertSame(VaultError.UserCancelled, fail(5, method)) // ERROR_CANCELED (not ours)
            assertSame(VaultError.UserCancelled, fail(13, method)) // negative button (never shown)
            assertSame(VaultError.PasscodeNotSet, fail(14, method)) // ERROR_NO_DEVICE_CREDENTIAL
            assertSame(VaultError.TimedOut, fail(3, method)) // ERROR_TIMEOUT (system)
            assertTrue(fail(4, method) is VaultError.Keystore) // ERROR_NO_SPACE: anything else
            assertTrue(fail(99, method) is VaultError.Keystore)
        }
        // Biometry unusable → one retry with the screen lock; on the screen-lock path it is final.
        for (code in listOf(7, 9, 1, 11, 12, 2, 8, 15)) {
            assertSame("code $code", HistoryKeyVault.PromptOutcome.RetryWithPasscode, outcome(code, UnlockMethod.BiometryPreferred))
            val final = fail(code, UnlockMethod.PasscodeOnly)
            assertTrue("code $code", final is VaultError.Keystore && final.detail == code)
        }
    }

    @Test
    fun cancelIsFinal() = runTest {
        val auth = FakeVaultAuthenticator(Step.Error(10))
        val vault = vault(auth)
        vault.store(historyKey, user)
        assertThrowsVault(VaultError.UserCancelled) { vault.unlock(user) }
        assertEquals(1, auth.prompts.size)
        assertTrue("cancel never clears", vault.hasBlob(user))
    }

    @Test
    fun aLockedOutFingerprintRetriesOnceWithTheScreenLockAndAFreshCipher() = runTest {
        val auth = FakeVaultAuthenticator(Step.Error(7), Step.Succeed)
        val vault = vault(auth)
        vault.store(historyKey, user)
        assertArrayEquals(historyKey, vault.unlock(user, UnlockMethod.BiometryPreferred))
        assertEquals(listOf(UnlockMethod.BiometryPreferred, UnlockMethod.PasscodeOnly), auth.prompts.map { it.second })
        assertNotSame("the retry needs a freshly initialised cipher", auth.prompts[0].first, auth.prompts[1].first)
    }

    @Test
    fun theRetryFailingTooIsAKeystoreError() = runTest {
        val auth = FakeVaultAuthenticator(Step.Error(7), Step.Error(7))
        val vault = vault(auth)
        vault.store(historyKey, user)
        val error = runCatching { vault.unlock(user) }.exceptionOrNull()
        assertTrue(error.toString(), error is VaultError.Keystore && error.detail == 7)
        assertEquals(2, auth.prompts.size)
    }

    @Test
    fun theRetryCancelledIsUserCancelled() = runTest {
        val auth = FakeVaultAuthenticator(Step.Error(11), Step.Error(10))
        val vault = vault(auth)
        vault.store(historyKey, user)
        assertThrowsVault(VaultError.UserCancelled) { vault.unlock(user) }
    }

    @Test
    fun passcodeOnlyNeverRetries() = runTest {
        val auth = FakeVaultAuthenticator(Step.Error(7), Step.Succeed)
        val vault = vault(auth)
        vault.store(historyKey, user)
        assertThrowsVault(VaultError.Keystore::class.java) { vault.unlock(user, UnlockMethod.PasscodeOnly) }
        assertEquals(listOf(UnlockMethod.PasscodeOnly), auth.prompts.map { it.second })
    }

    @Test
    fun theSystemTimeoutIsTimedOut() = runTest {
        val vault = vault(FakeVaultAuthenticator(Step.Error(3)))
        vault.store(historyKey, user)
        assertThrowsVault(VaultError.TimedOut) { vault.unlock(user) }
    }

    @Test
    fun anAuthenticatorFailureIsPassedOn() = runTest {
        val vault = vault(FakeVaultAuthenticator(Step.Throw(VaultError.UserCancelled)))
        vault.store(historyKey, user)
        assertThrowsVault(VaultError.UserCancelled) { vault.unlock(user) }
    }

    /** iOS races the sheet against 90 s (`HistoryKeyVault.swift:45-48`, `:116-151`) and dismisses it. */
    @Test
    fun aPromptLeftUpForNinetySecondsTimesOutAndIsDismissed() = runTest {
        val auth = FakeVaultAuthenticator(Step.Hang)
        val vault = vault(auth)
        vault.store(historyKey, user)
        val unlock = async { runCatching { vault.unlock(user) } }
        auth.shown.await()
        advanceTimeBy(89_999)
        runCurrent()
        assertFalse(unlock.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertSame(VaultError.TimedOut, unlock.await().exceptionOrNull())
        assertEquals(1, auth.cancelled)
        assertEquals(90_000L, HistoryKeyVault.AUTH_TIMEOUT.inWholeMilliseconds)
    }

    @Test
    fun aWrongTagIsOpenFailed() = runTest {
        val vault = vault()
        vault.store(historyKey, user)
        val r = record().toMutableMap()
        val blob = B64.decodeStrict(r["blob"]!!.jsonPrimitive.content)!!
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        r["blob"] = JsonPrimitive(B64.encode(blob))
        SealedFile(recordFile, sealer).write(JsonObject(r).toString().toByteArray())
        assertThrowsVault(VaultError.OpenFailed) { vault.unlock(user) }
    }

    private suspend fun assertThrowsVault(expected: VaultError, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertSame("expected $expected, got $error", expected, error)
    }

    private suspend fun assertThrowsVault(expected: Class<out VaultError>, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("expected ${expected.simpleName}, got $error", expected.isInstance(error))
    }
}
