package de.corespace.shroud.core.model

import java.util.UUID

/**
 * Ids above the byte level are [UUID]s (plan §1.1 rule 4, conflict C1). Strings exist only on the
 * wire and on disk, always in the lower-case form [wire] writes.
 *
 * iOS decodes ids as `UUID` and fails the whole DTO on a malformed one (`UUID(uuidString:)` is
 * strict, `MessageModels.swift:148-152`); [parse] keeps that strictness, which
 * `UUID.fromString` does not (it reads `1-1-1-1-1` as `00000001-0001-0001-0001-000000000001`).
 */
object Ids {
    private val CANONICAL = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Strict canonical 8-4-4-4-12 hex, any case, trimmed; anything else → null (UUID.fromString is lenient). */
    fun parse(text: String?): UUID? {
        val trimmed = text?.trim() ?: return null
        if (!CANONICAL.matches(trimmed)) return null
        return UUID.fromString(trimmed)
    }

    /** [parse] or [IllegalArgumentException]. The message never repeats the input (it may be a token by mistake). */
    fun require(text: String): UUID = parse(text) ?: throw IllegalArgumentException("Not a canonical UUID.")

    /** `id.toString()`: lower-case — the only form on the wire and on disk (iOS `uuidString.lowercased()`). */
    fun wire(id: UUID): String = id.toString()

    /**
     * The "lower id" rule (ratchet initiator, sorted pairs): compares the lower-case wire strings,
     * as iOS does (`MessageCrypto.swift:155`, `ourUserID.uuidString.lowercased() <
     * peerUserID.uuidString.lowercased()`). Never `UUID.compareTo`, which compares signed longs and
     * disagrees for ids whose first hex digit is 8–f.
     */
    fun precedes(a: UUID, b: UUID): Boolean = wire(a) < wire(b)
}
