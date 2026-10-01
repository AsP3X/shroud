package de.corespace.shroud.core.model

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Immutable bytes with value equality and a cached hash — the Kotlin stand-in for Swift `Data`
 * inside models (plan C9). Only small payloads belong here (previews, posters, waveforms, keys);
 * large media never enters a model (C8), so comparing by content stays cheap.
 *
 * [toString] never prints the content: a model holding key bytes must be safe to log by accident.
 */
class Bytes private constructor(private val value: ByteArray) {
    val size: Int get() = value.size

    /** A copy: callers may change it freely. */
    fun toByteArray(): ByteArray = value.copyOf()

    /** Reads the bytes without copying them. */
    fun inputStream(): InputStream = ByteArrayInputStream(value)

    // The String.hashCode idiom: computing twice on a race gives the same value, so no lock.
    private var hash: Int = 0
    private var hashIsZero: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Bytes) return false
        if (other.value.size != value.size || other.hashCode() != hashCode()) return false
        return other.value.contentEquals(value)
    }

    /** Computed once, on first use. */
    override fun hashCode(): Int {
        var h = hash
        if (h == 0 && !hashIsZero) {
            h = value.contentHashCode()
            if (h == 0) hashIsZero = true else hash = h
        }
        return h
    }

    override fun toString(): String = "Bytes(size=$size)"

    companion object {
        /** Copies [bytes]: later changes to the array do not reach the result. */
        fun of(bytes: ByteArray): Bytes = Bytes(bytes.copyOf())

        /** Takes ownership of [bytes] without copying; the caller must not change the array afterwards. */
        fun adopt(bytes: ByteArray): Bytes = Bytes(bytes)
    }
}
