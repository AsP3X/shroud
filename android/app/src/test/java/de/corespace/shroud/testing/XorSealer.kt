package de.corespace.shroud.testing

import de.corespace.shroud.core.storage.Sealer

/**
 * A reversible stand-in for the Keystore (`KeystoreSealer`): JVM tests have none. XOR with
 * `0x5A`, so a sealed file never equals its plaintext and a test can still see it was written
 * through the sealer. Not a cipher — tests only.
 */
class XorSealer : Sealer {
    override fun seal(plaintext: ByteArray): ByteArray = ByteArray(plaintext.size) { (plaintext[it].toInt() xor 0x5A).toByte() }

    override fun open(sealed: ByteArray): ByteArray = seal(sealed)
}
