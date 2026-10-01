package de.corespace.shroud.core.crypto

import java.security.SecureRandom

/**
 * Where crypto code draws its random bytes (ephemeral keys, GCM nonces, ratchet keys, media keys,
 * device-name nonces, vault wrap keys). Injected so golden-vector tests can pin every output
 * (crypto spec §1.3); production code uses [SystemEntropy].
 */
fun interface Entropy {
    /** [count] fresh bytes. The caller owns (and may zero) the returned array. */
    fun bytes(count: Int): ByteArray
}

/** The platform CSPRNG (`SecureRandom`, CryptoKit's `SystemRandomNumberGenerator` on iOS). */
object SystemEntropy : Entropy {
    private val random = SecureRandom()

    override fun bytes(count: Int): ByteArray {
        require(count >= 0) { "negative byte count" }
        return ByteArray(count).also(random::nextBytes)
    }
}

/**
 * Tests only: hands out [chunks] in order, one per draw. A draw whose size differs from the next
 * chunk, or a draw after the last one, throws [IllegalStateException] — a vector built on the
 * wrong draw order must fail loudly, never fall back to real randomness.
 */
class ScriptedEntropy(vararg chunks: ByteArray) : Entropy {
    private val queue = ArrayDeque(chunks.map { it.copyOf() })

    /** Chunks not drawn yet; a test can assert it reached 0. */
    val remaining: Int
        @Synchronized get() = queue.size

    @Synchronized
    override fun bytes(count: Int): ByteArray {
        val next = queue.removeFirstOrNull() ?: throw IllegalStateException("ScriptedEntropy is exhausted (asked for $count bytes)")
        check(next.size == count) { "ScriptedEntropy: asked for $count bytes, next chunk has ${next.size}" }
        return next
    }
}
