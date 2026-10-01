package de.corespace.shroud.core.crypto

/**
 * The safety number of two X25519 identity public keys: a stable, order-independent fingerprint
 * two people compare in person or over a second channel (iOS
 * `ios/shroud/Services/Crypto/IdentitySafetyNumber.swift:10-36`, web `web/src/crypto/safetyNumber.ts`;
 * crypto spec §17.3, contacts spec §4.9):
 *
 * ```
 * (first, second) = the two keys in unsigned byte order (Data.lexicographicallyPrecedes)
 * d               = SHA-256(first ‖ second)
 * group i (0…11)  = ((d[2i % 32] << 16) | (d[(2i+1) % 32] << 8) | d[3i % 32]) % 100000, 5 digits
 * ```
 *
 * 30 bytes give 10 groups; the last two wrap so the whole hash is used (`:26`). The byte order is
 * **unsigned** ([lexLess]): a signed compare would give a different number for about half of all
 * key pairs (crypto R7). Never logs key bytes.
 */
object IdentitySafetyNumber {
    const val GROUPS = 12
    const val GROUP_DIGITS = 5

    /** 12 groups of 5 digits joined by single spaces (71 characters), the same whichever key is "ours". */
    fun displayString(localIdentity: ByteArray, peerIdentity: ByteArray): String {
        val (first, second) = if (lexLess(localIdentity, peerIdentity)) localIdentity to peerIdentity else peerIdentity to localIdentity
        val digest = Primitives.sha256(first + second)
        return (0 until GROUPS).joinToString(" ") { i ->
            val a = digest[(i * 2) % digest.size].toInt() and 0xFF
            val b = digest[(i * 2 + 1) % digest.size].toInt() and 0xFF
            val c = digest[(i * 3) % digest.size].toInt() and 0xFF
            val value = (a shl 16) or (b shl 8) or c
            (value % 100_000).toString().padStart(GROUP_DIGITS, '0')
        }
    }
}
