package de.corespace.shroud.core.storage

/**
 * A JVM stand-in for `KeystoreSealer` whose failures a test switches on: XOR with `0x5A` (like the
 * test kit's `XorSealer`, so a sealed file never equals its plaintext), plus
 * - [readFailure]: every [openClassified] reports it instead of opening (a locked phone, a vanished
 *   key, a transient Keystore error);
 * - [sealFails]: every [seal] throws (the phone locked between the check and the write).
 * Tests only — not a cipher.
 */
class ScriptedSealer : Sealer {
    @Volatile
    var readFailure: SealResult? = null

    @Volatile
    var sealFails = false

    /** Seals done so far; a test can assert a write was (not) attempted. */
    @Volatile
    var sealCount = 0
        private set

    override fun seal(plaintext: ByteArray): ByteArray {
        if (sealFails) throw IllegalStateException("sealer refuses (scripted)")
        sealCount++
        return xor(plaintext)
    }

    override fun open(sealed: ByteArray): ByteArray = when (val result = openClassified(sealed)) {
        is SealResult.Opened -> result.bytes
        else -> throw IllegalStateException("sealer refuses (scripted): $result")
    }

    override fun openClassified(sealed: ByteArray): SealResult = readFailure ?: SealResult.Opened(xor(sealed))

    private fun xor(bytes: ByteArray) = ByteArray(bytes.size) { (bytes[it].toInt() xor 0x5A).toByte() }
}
