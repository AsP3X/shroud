package de.corespace.shroud.core.messaging.local

import de.corespace.shroud.testing.TempDirRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * iOS `MessagingLocalRepositoryTests.swift`: hydrate runs on the unlock path, so it must not
 * rewrite every cached plaintext each time — but a cache entry that no longer matches the stored
 * message still has to be refreshed.
 */
class MessagingLocalRepositoryTest {
    @get:Rule
    val temp = TempDirRule()

    private val fixture by lazy { StoreFixture(temp.noBackupFilesDir) }
    private val peerId = UUID.randomUUID()
    private val messageId = UUID.randomUUID()

    /** `testHydrateLeavesAMatchingPlaintextEntryUntouched` (`:20-30`). */
    @Test
    fun hydrateLeavesAMatchingPlaintextEntryUntouched() {
        val repository = seededRepository()
        fixture.backdate(fixture.plaintextFile(messageId))

        val state = repository.hydrate(fixture.userId)

        assertEquals(listOf("hello"), state.threads[peerId]?.map { it.text })
        assertEquals(StoreFixture.OLD_MILLIS, fixture.plaintextFile(messageId).lastModified())
    }

    /** `testHydrateRefreshesAPlaintextEntryThatDiffers` (`:32-43`). */
    @Test
    fun hydrateRefreshesAPlaintextEntryThatDiffers() {
        val repository = seededRepository()
        repository.savePlaintext(messageId, "stale".toByteArray())

        repository.hydrate(fixture.userId)

        assertEquals("hello", repository.plaintext(messageId)?.toString(Charsets.UTF_8))
        // A fresh repository reads the disk copy, not the in-memory one.
        val reopened = fixture.repository()
        assertEquals("hello", reopened.plaintext(messageId)?.toString(Charsets.UTF_8))
    }

    /** `seededRepository` (`:51-76`). */
    private fun seededRepository(): MessagingLocalRepository {
        val repository = fixture.repository()
        val stored = StoredMessage(
            id = messageId,
            peerUserId = peerId,
            senderUserId = fixture.userId,
            text = "hello",
            createdAt = fixture.now,
            isMine = true,
            deleted = false,
            receipt = "sent",
            kind = "text",
            pendingSync = null,
        )
        fixture.persist(repository, mapOf(peerId to listOf(stored.toChatMessage { false })))
        assertTrue(fixture.plaintextFile(messageId).exists())
        return repository
    }
}
