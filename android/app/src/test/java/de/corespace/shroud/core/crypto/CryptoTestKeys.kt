package de.corespace.shroud.core.crypto

import java.util.UUID

/** An X25519 identity for tests: iOS `Curve25519.KeyAgreement.PrivateKey()` and its public key. */
class TestIdentity(val private: ByteArray) {
    val public: ByteArray = Primitives.x25519Public(private)

    companion object {
        /** A fresh random identity. */
        fun random(): TestIdentity = TestIdentity(SystemEntropy.bytes(32))

        /** `Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(repeating: byte, count: 32))`. */
        fun filled(byte: Int): TestIdentity = TestIdentity(CryptoFixtures.bytes(byte, 32))
    }
}

/** Small helpers shared by the W1-CRYPTO tests. */
object CryptoFixtures {
    /** [count] bytes of [fill] (Swift `Data(repeating:count:)`). */
    fun bytes(fill: Int, count: Int): ByteArray = ByteArray(count) { fill.toByte() }

    /** [count] bytes counting up from [start] (wrapping at 0xff). */
    fun sequence(start: Int, count: Int): ByteArray = ByteArray(count) { (start + it).toByte() }

    /**
     * Two fresh user ids, sorted by their lower-case strings so `first` is the deterministic ratchet
     * initiator (`ios/shroudTests/DoubleRatchetTests.swift:17-21`).
     */
    fun sortedUserIds(): Pair<UUID, UUID> {
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID()).sortedBy { it.toString() }
        return ids[0] to ids[1]
    }

    /** The `v` of an envelope (the tests' `Peek` structs). */
    fun version(envelope: ByteArray): Int =
        CryptoJson.parseToJsonElement(String(envelope, Charsets.UTF_8)).let {
            (it as kotlinx.serialization.json.JsonObject)["v"].toString().toInt()
        }
}
