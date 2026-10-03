package de.corespace.shroud.core.auth

import java.security.MessageDigest
import java.util.Base64

/**
 * The account name, checked here and sent only as SHA-256. Same rules as `auth/username.rs`.
 * Standard Base64, not Base64url.
 */
object UsernameHash {
    private val reservedExact = setOf(
        "admin", "administrator", "support", "help", "shroud", "system", "root", "security",
        "null", "undefined", "api", "www", "mail", "email", "mod", "moderator", "staff",
        "official", "everyone", "all", "me", "self", "owner",
    )
    private val reservedPrefixes = listOf("shroud_", "system_", "admin_", "support_")

    /** Lowercase `[a-z0-9_]`, 3–32, and not a reserved name. Null when the name is acceptable. */
    fun problem(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.length !in 3..32) return "Username must be between 3 and 32 characters."
        if (!trimmed.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }) {
            return "Username may only contain letters, digits, and underscores."
        }
        val folded = trimmed.lowercase()
        if (folded in reservedExact || reservedPrefixes.any { folded.startsWith(it) }) {
            return "That username is reserved."
        }
        return null
    }

    fun normalize(raw: String): String {
        problem(raw)?.let { throw IllegalArgumentException(it) }
        return raw.trim().lowercase()
    }

    /** Standard Base64 of SHA-256 over the UTF-8 normalized name. */
    fun digest(normalized: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8)))
}
