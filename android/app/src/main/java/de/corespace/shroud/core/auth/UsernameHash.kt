package de.corespace.shroud.core.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The account name, checked here and sent as a slow salted digest. Same rules as `auth/username.rs`.
 * Standard Base64, not Base64url.
 *
 * [digest] stays SHA-256: it is the legacy login value and the local fingerprint of
 * `username + "." + public key`.
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

    private val argon2Cache = ConcurrentHashMap<String, String>()

    /**
     * Argon2id of a normalized name. Cached for this process so a device-limit retry, and the
     * unit tests, do not pay the cost twice.
     */
    fun argon2id(normalized: String, params: UsernameKdfParams): String {
        val key = "$normalized\n${params.cacheKey}"
        argon2Cache[key]?.let { return it }
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(params.iterations)
                .withMemoryAsKB(params.memoryKiB)
                .withParallelism(params.parallelism)
                .withSalt(params.salt)
                .build(),
        )
        val out = ByteArray(params.outputBytes)
        val written = generator.generateBytes(normalized.toByteArray(Charsets.UTF_8), out)
        check(written == out.size)
        val digest = Base64.getEncoder().encodeToString(out)
        argon2Cache[key] = digest
        return digest
    }
}

const val USERNAME_KDF_WEAK = "This server's username protection is too weak to sign in."

/** Checked parameters from `GET /auth/username-kdf`. */
class UsernameKdfParams(
    val salt: ByteArray,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val outputBytes: Int,
) {
    val cacheKey: String
        get() = "$memoryKiB:$iterations:$parallelism:$outputBytes:${Base64.getEncoder().encodeToString(salt)}"

    companion object {
        val TEST = UsernameKdfParams(
            salt = Base64.getDecoder().decode("ABEiM0RVZneImaq7zN3u/w=="),
            memoryKiB = 65536,
            iterations = 8,
            parallelism = 1,
            outputBytes = 32,
        )
    }
}
