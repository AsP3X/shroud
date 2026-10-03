package de.corespace.shroud.core.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * The account's contact-name book, shared by its devices through the server, which can't read
 * it. The same bytes as web `crypto/contactBook.ts` and iOS `ContactNameBook`.
 *
 * A name reaches a device only when that contact's app seals it to the account. A device that
 * has opened names keeps them in this book, so a browser (which keeps no contact list) or a new
 * phone learns them from the account's other devices at once.
 *
 * key       = HKDF-SHA256(historyKey, salt "shroud-v1", info "shroud-contact-names-v1", 32)
 * aad       = "shroud-contact-names-v1:" + lowercase owner user id
 * plaintext = UTF-8 JSON {"v":1,"names":{"<lowercase peer id>":"<username>",…}}, ids sorted,
 *             no spaces, then spaces to the next multiple of 1024 bytes
 * sealed    = nonce(12) ‖ AES-256-GCM ciphertext ‖ tag(16), standard Base64
 */
object ContactNameBookSeal {
    private const val LABEL = "shroud-contact-names-v1"
    private const val PAD_TO = 1024
    private val SALT = "shroud-v1".toByteArray(Charsets.UTF_8)
    private val NAME = Regex("^[a-z0-9_]{3,32}$")
    private val ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** Only well-formed ids and usernames survive, whichever device wrote the book; sorted by id. */
    fun clean(names: Map<String, String>): Map<String, String> =
        names.entries
            .mapNotNull { (id, name) -> id.lowercase().takeIf { ID.matches(it) && NAME.matches(name) }?.let { it to name } }
            .toMap()
            .toSortedMap()

    fun seal(names: Map<String, String>, owner: UUID, historyKey: ByteArray, nonce: ByteArray? = null): String {
        val entries = clean(names).entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
        val json = "{\"v\":1,\"names\":{$entries}}".toByteArray(Charsets.UTF_8)
        val padded = ByteArray((json.size + PAD_TO) / PAD_TO * PAD_TO) { 0x20 }
        json.copyInto(padded)
        val key = key(historyKey)
        try {
            val n = nonce ?: SystemEntropy.bytes(Primitives.GCM_NONCE_BYTES)
            return B64.encode(Primitives.aesGcmSeal(key, n, padded, aad(owner)))
        } finally {
            key.fill(0)
        }
    }

    /** Null when the book doesn't open: another account's key, or damaged. */
    fun open(sealed: String?, owner: UUID, historyKey: ByteArray): Map<String, String>? {
        if (sealed.isNullOrEmpty()) return null
        val key = key(historyKey)
        return try {
            val plain = Primitives.aesGcmOpen(key, B64.decodeStrict(sealed) ?: return null, aad(owner))
            val json = Json.parseToJsonElement(String(plain, Charsets.UTF_8).trimEnd()) as? JsonObject ?: return null
            if (json["v"]?.jsonPrimitive?.intOrNull != 1) return null
            val names = json["names"] as? JsonObject ?: return null
            clean(names.mapNotNull { (id, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.let { id to it.content } }.toMap())
        } catch (_: Exception) {
            null
        } finally {
            key.fill(0)
        }
    }

    private fun key(historyKey: ByteArray): ByteArray =
        Primitives.hkdf(historyKey, SALT, LABEL.toByteArray(Charsets.UTF_8), 32)

    private fun aad(owner: UUID): ByteArray = "$LABEL:${owner.toString().lowercase()}".toByteArray(Charsets.UTF_8)
}
