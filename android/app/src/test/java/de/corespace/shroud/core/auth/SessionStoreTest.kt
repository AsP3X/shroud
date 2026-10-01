package de.corespace.shroud.core.auth

import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.core.storage.Sealer
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.testing.TempDirRule
import de.corespace.shroud.testing.XorSealer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** `ios/shroudTests/SessionStoreTests.swift` + `DeviceDataWipeTests.noSessionOnlyWhenTheKeychainSaysSo`; settings-lock ST.5. */
class SessionStoreTest {
    @get:Rule val temp = TempDirRule()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }
    private val sessionFile get() = File(temp.noBackupFilesDir, "session.sealed")
    private val anchorFile get() = File(temp.noBackupFilesDir, "device-anchor.sealed")

    private fun store(seal: StorageSeal? = null, anchorSealer: Sealer = XorSealer()) = SessionStore(
        SealedFile(sessionFile, XorSealer()),
        SealedFile(anchorFile, anchorSealer),
        json,
        seal,
    )

    private val userId = UUID.randomUUID().toString()
    private val deviceId = UUID.randomUUID().toString()

    @Test
    fun deviceAnchorSurvivesSessionClear() {
        val store = store()
        store.save(Session("tok", userId, "alice", null, deviceId))
        store.clear()
        assertNull(store.session)
        assertNull(store().session)
        assertEquals(deviceId, store.anchorFor("alice"))
        // Compared trimmed and case-insensitively (`loadDeviceID(matchingUsername:)`).
        assertEquals(deviceId, store.anchorFor(" Alice "))
        assertNull(store.anchorFor("bob"))
        assertNull(store.anchorFor(""))
        store.wipe()
        assertNull(store.anchorFor("alice"))
    }

    @Test
    fun theAnchorNameIsNormalised() {
        // ST3: whatever case the server sent, the anchor is stored lower-cased.
        store().save(Session("tok", userId, "Alice", null, deviceId))
        assertTrue(String(XorSealer().open(anchorFile.readBytes())).contains("\"username\":\"alice\""))
    }

    @Test
    fun aFailingAnchorWriteDoesNotFailTheSignIn() {
        // ST2: iOS ignores an anchor write error (`try?`); the session is stored all the same.
        val failing = object : Sealer {
            override fun seal(plaintext: ByteArray): ByteArray = throw IllegalStateException("keystore")
            override fun open(sealed: ByteArray): ByteArray = throw IllegalStateException("keystore")
        }
        val store = store(anchorSealer = failing)
        store.save(Session("tok", userId, "alice", null, deviceId))
        assertEquals("tok", store().session?.token)
    }

    @Test
    fun noSessionOnlyWhenNoFileExists() {
        val store = store()
        assertTrue(store.hasNoSession())
        assertFalse(store.isUnreadableForGood)
        store.save(Session("tok", userId, "alice", null, deviceId))
        assertFalse(store.hasNoSession())
        store.clear()
        assertTrue(store.hasNoSession())
    }

    @Test
    fun aFileThatDoesNotOpenIsNeitherASessionNorNoSession() {
        // ST1: `session == null` for absent and unreadable alike; only the file tells them apart.
        sessionFile.parentFile?.mkdirs()
        sessionFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val store = store()
        assertNull(store.session)
        assertFalse(store.hasNoSession())
        assertTrue(store.isUnreadableForGood)
        store.clear()
        assertTrue(store.hasNoSession())
        assertFalse(store.isUnreadableForGood)
    }

    @Test
    fun aTransientReadFailureIsNotUnreadableForGood() {
        store().save(Session("tok", userId, "alice", null, deviceId))
        val flaky = object : Sealer {
            override fun seal(plaintext: ByteArray): ByteArray = plaintext
            override fun open(sealed: ByteArray): ByteArray = throw IllegalStateException("transient")
        }
        val store = SessionStore(SealedFile(sessionFile, flaky), SealedFile(anchorFile, XorSealer()), json)
        assertNull(store.session)
        assertFalse(store.hasNoSession())
        assertFalse(store.isUnreadableForGood)
    }

    @Test
    fun aRecordOfAnOlderBuildStillReads() {
        // Older builds persisted the 401 streak in the record; it is read and ignored (ST4).
        sessionFile.parentFile?.mkdirs()
        val legacy = """{"session":{"token":"tok","userId":"$userId","username":"alice","shareCode":null,"deviceId":"$deviceId"},"authFailures":2}"""
        sessionFile.writeBytes(XorSealer().seal(legacy.toByteArray()))
        assertEquals("tok", store().session?.token)
    }

    @Test
    fun nothingIsWrittenWhileAWipeRuns() {
        val seal = StorageSeal()
        val store = store(seal)
        seal.seal()
        store.save(Session("tok", userId, "alice", null, deviceId))
        assertTrue(store.hasNoSession())
        assertFalse(anchorFile.exists())
    }

    @Test
    fun theTokenNeverPrints() {
        assertFalse(Session("secret-token", userId, "alice", null, deviceId).toString().contains("secret-token"))
    }
}
