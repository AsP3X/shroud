package de.corespace.shroud.core.contacts

import android.content.SharedPreferences
import de.corespace.shroud.core.crypto.ContactNameBookSeal
import de.corespace.shroud.core.crypto.ContactNameSeal
import de.corespace.shroud.core.crypto.IdentityKeyMaterial
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactNamesDto
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Shown until a mutual contact's seal opens on this phone. */
const val CONTACT_PLACEHOLDER = "Contact"

/**
 * Usernames this phone has opened. Stored in the preferences file Log Out wipes.
 * The server never has them.
 */
class ContactNameBook(private val prefs: SharedPreferences) {
    fun name(owner: UUID, peer: UUID): String? {
        val stored = read(bookKey(owner)).optString(peer.toString(), "")
        return stored.takeIf { NAME.matches(it) }
    }

    fun remember(owner: UUID, peer: UUID, name: String) {
        if (!NAME.matches(name)) return
        val map = read(bookKey(owner))
        map.put(peer.toString(), name)
        write(bookKey(owner), map)
    }

    /** Every name this phone has, by lowercase peer id (for the shared [ContactNameBookSeal]). */
    fun all(owner: UUID): Map<String, String> {
        val map = read(bookKey(owner))
        return map.keys().asSequence().associateWith { map.optString(it, "") }.filterValues { NAME.matches(it) }
    }

    fun retain(owner: UUID, peers: Collection<UUID>) {
        val keep = peers.map { it.toString() }.toSet()
        for (key in listOf(bookKey(owner), publishedKey(owner))) {
            val map = read(key)
            val drop = map.keys().asSequence().filter { it !in keep }.toList()
            if (drop.isEmpty()) continue
            drop.forEach { map.remove(it) }
            write(key, map)
        }
    }

    fun published(owner: UUID, peer: UUID): String? =
        read(publishedKey(owner)).optString(peer.toString(), "").ifEmpty { null }

    fun rememberPublished(owner: UUID, peer: UUID, fingerprint: String) {
        val map = read(publishedKey(owner))
        map.put(peer.toString(), fingerprint)
        write(publishedKey(owner), map)
    }

    private fun read(key: String): JSONObject =
        try {
            JSONObject(prefs.getString(key, null) ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }

    private fun write(key: String, map: JSONObject) {
        prefs.edit().putString(key, map.toString()).apply()
    }

    private fun bookKey(owner: UUID) = "contact.names.$owner"
    private fun publishedKey(owner: UUID) = "contact.names.published.$owner"

    private companion object {
        val NAME = Regex("^[a-z0-9_]{3,32}$")
    }
}

/**
 * Fills contact rows from names this phone already has. The production exchange also opens
 * seals and publishes this account's name. Tests use [KEEP_LOCAL], which never talks to a peer.
 */
open class ContactNameExchange {
    open suspend fun apply(
        token: String,
        contacts: List<ContactItemDto>,
        previous: List<ContactItemDto>,
    ): List<ContactItemDto> = keepLocal(contacts, previous)

    companion object {
        val KEEP_LOCAL = ContactNameExchange()

        fun keepLocal(contacts: List<ContactItemDto>, previous: List<ContactItemDto>): List<ContactItemDto> {
            val known = previous.associate { it.userId to it.username }
            return contacts.map { row ->
                val kept = known[row.userId]?.takeIf { it.isNotBlank() && it != CONTACT_PLACEHOLDER }
                row.copy(username = kept ?: row.username.ifBlank { CONTACT_PLACEHOLDER })
            }
        }
    }
}

/**
 * Opens each contact's seal and publishes this account's username, sealed to them.
 * A key change that has not been trusted is skipped.
 */
class MutualContactNames(
    private val book: ContactNameBook,
    private val owner: () -> UUID?,
    private val username: () -> String?,
    private val keys: () -> Pair<ByteArray, ByteArray>?,
    private val peerKey: suspend (UUID) -> ByteArray,
    private val publish: suspend (String, UUID, String) -> Unit,
    /** A copy of the history key while chats are unlocked; the caller's copy is wiped here. */
    private val historyKey: () -> ByteArray? = { null },
    private val fetchBook: suspend (String) -> ContactNamesDto = { ContactNamesDto() },
    private val storeBook: suspend (String, String, Long) -> Unit = { _, _, _ -> },
) : ContactNameExchange() {
    override suspend fun apply(
        token: String,
        contacts: List<ContactItemDto>,
        previous: List<ContactItemDto>,
    ): List<ContactItemDto> {
        val me = owner() ?: return keepLocal(contacts, previous)
        val still = contacts.map { it.userId }.toSet()
        for (row in previous) {
            if (row.userId in still && row.username.isNotBlank() && row.username != CONTACT_PLACEHOLDER) {
                book.remember(me, row.userId, row.username)
            }
        }
        book.retain(me, still)
        val name = username()?.takeIf { NAME.matches(it) }
        val material = keys()
        if (material != null) {
            val (privateKey, publicKey) = material
            try {
                for (contact in contacts) {
                    try {
                        val theirPublic = peerKey(contact.userId)
                        contact.sealedName?.let { sealed ->
                            ContactNameSeal.open(sealed, privateKey, publicKey, theirPublic)?.let {
                                book.remember(me, contact.userId, it)
                            }
                        }
                        if (name == null) continue
                        val fingerprint = fingerprint(name, theirPublic)
                        if (book.published(me, contact.userId) == fingerprint) continue
                        val sealed = ContactNameSeal.seal(name, privateKey, publicKey, theirPublic)
                        publish(token, contact.userId, sealed)
                        book.rememberPublished(me, contact.userId, fingerprint)
                    } catch (_: Exception) {
                        // No key yet, or a key change waiting to be trusted.
                    }
                }
            } finally {
                privateKey.fill(0)
            }
            syncBook(token, me, contacts.map { it.userId.toString() }.toSet())
        }
        return contacts.map { row ->
            row.copy(username = book.name(me, row.userId) ?: CONTACT_PLACEHOLDER)
        }
    }

    /**
     * Merges the book the account's devices share with this phone's names, and writes the union
     * back when this phone knows names the book lacks. A write another device beat is read again
     * and merged once more, so no device's names are lost. Offline, the names here still show.
     */
    private suspend fun syncBook(token: String, me: UUID, contactIds: Set<String>) {
        val key = historyKey() ?: return
        try {
            repeat(3) {
                val remote = try {
                    fetchBook(token)
                } catch (_: Exception) {
                    return
                }
                val remoteNames = ContactNameBookSeal.open(remote.sealed, me, key) ?: emptyMap()
                for ((id, known) in remoteNames) {
                    if (id !in contactIds) continue
                    val peer = UUID.fromString(id)
                    if (book.name(me, peer) == null) book.remember(me, peer, known)
                }
                val merged = (remoteNames + book.all(me)).filterKeys { it in contactIds }
                if (merged == remoteNames) return
                try {
                    storeBook(token, ContactNameBookSeal.seal(merged, me, key), remote.version)
                    return
                } catch (error: ApiError.Server) {
                    if (error.code != "VERSION_CONFLICT") return
                } catch (_: Exception) {
                    return
                }
            }
        } finally {
            key.fill(0)
        }
    }

    private fun fingerprint(name: String, theirPublic: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(name.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(theirPublic)
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    private companion object {
        val NAME = Regex("^[a-z0-9_]{3,32}$")
    }
}

/** Copies the agreement keys out of an unlocked identity. Null while chats are locked. */
fun IdentityKeyMaterial.agreementKeys(): Pair<ByteArray, ByteArray> =
    agreementPrivateKey.copyOf() to agreementPublic.copyOf()
