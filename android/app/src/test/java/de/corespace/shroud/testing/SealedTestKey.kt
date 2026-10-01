package de.corespace.shroud.testing

/**
 * The 32-byte test master key `0x5A × 32`, iOS `SealedTestKey.historyKey`
 * (`ios/shroudTests/SealedTestKey.swift:8`; crypto §8, §16.3): the `historyKey` of the at-rest
 * vectors (`LocalHistoryCryptoTests`) and of every sealed-store test.
 */
object SealedTestKey {
    const val FILL: Byte = 0x5A
    const val SIZE = 32
    const val HEX = "5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a"

    /** A fresh copy on every call: code under test may zero the arrays it is given. */
    fun bytes(): ByteArray = ByteArray(SIZE) { FILL }
}
