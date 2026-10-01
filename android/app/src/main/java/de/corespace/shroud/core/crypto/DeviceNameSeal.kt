package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.model.Ids
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID

/**
 * Seals a device's name so only the account's own devices read it (iOS
 * `ios/shroud/Services/Crypto/DeviceNameSeal.swift`, web `web/src/crypto/deviceName.ts`,
 * `docs/architecture.md` "Sealed device names"; crypto spec §17.2). The server keeps only
 * `devices.sealed_name`:
 *
 * ```
 * key    = HKDF-SHA256(historyKey, salt "shroud-v1", info "shroud-device-name-v1", 32)
 * aad    = "shroud-device-name-v1:" + lower-case device id      (a name cannot move to another device)
 * padded = kind | (custom ? 0x80 : 0) ‖ UTF-8 name ‖ 0x80 ‖ 0x00… to 128 bytes   (the length does not show)
 * sealed = nonce (12) ‖ AES-256-GCM(key, nonce, padded, aad) (128) ‖ tag (16) = 156 bytes, standard Base64
 * ```
 *
 * Kind byte 4 = "Android app" is Android's addition (crypto D4, plan P4, approved 2026-10-01): iOS
 * and web read it from W1 on (X1-IOS, X1-WEB) and showed unknown kinds as "other" before, so writing
 * it is safe. Never logs names or keys.
 */
object DeviceNameSeal {
    /** What kind of client a device is; the icon of its row (`DeviceNameSeal.swift:18-23`, + [Android]). */
    enum class Kind(val byte: Int) {
        Other(0),
        IPhone(1),
        IPad(2),
        Web(3),

        /** Shroud for Android (P4); iOS and web show "Android app". */
        Android(4),
        ;

        companion object {
            /** The kind of [byte] (custom bit already removed); unknown values are [Other] (`DeviceNameSeal.swift:82`). */
            fun fromByte(byte: Int): Kind = entries.firstOrNull { it.byte == byte } ?: Other
        }
    }

    /**
     * A device's sealed label (`DeviceNameSeal.swift:25-30`).
     *
     * @property custom a person typed this name (a rename), rather than the device naming itself;
     *   the device then stops putting its own name back.
     */
    data class Label(val name: String, val kind: Kind, val custom: Boolean = false) {
        /** Never prints the name. */
        override fun toString(): String = "DeviceNameSeal.Label(kind=$kind, custom=$custom)"
    }

    /** iOS `DeviceNameSeal.SealError` (`:32-34`). */
    sealed class SealError(msg: String) : Exception(msg, null, false, false) {
        /** Nothing is left of the name after [normalize]. */
        object EmptyName : SealError("device name is empty")
    }

    /** Longest name in UTF-8 bytes; the kind and the padding need two bytes more (`:37`). */
    const val MAX_NAME_BYTES = 96

    private const val LABEL = "shroud-device-name-v1"
    private const val CUSTOM_BIT = 0x80
    private const val PADDED_BYTES = 128
    private const val SEALED_BYTES = Primitives.GCM_NONCE_BYTES + PADDED_BYTES + Primitives.GCM_TAG_BYTES
    private val SALT = utf8("shroud-v1")

    /**
     * Seals [label] for [deviceId] (`seal`, `DeviceNameSeal.swift:44-66`). The name is [normalize]d
     * first; nothing left → [SealError.EmptyName]. [nonce] is for test vectors only (12 bytes);
     * leave it out for a fresh random one.
     *
     * @return 156 bytes as standard Base64 (208 characters), the `sealed_name` of
     *   `PUT /api/v1/devices/{id}/name`.
     */
    fun seal(label: Label, deviceId: UUID, historyKey: ByteArray, nonce: ByteArray? = null): String {
        val name = utf8(normalize(label.name))
        if (name.isEmpty()) throw SealError.EmptyName
        require(nonce == null || nonce.size == Primitives.GCM_NONCE_BYTES) { "the nonce is 12 bytes" }
        val padded = ByteArray(PADDED_BYTES)
        padded[0] = (label.kind.byte or (if (label.custom) CUSTOM_BIT else 0)).toByte()
        name.copyInto(padded, destinationOffset = 1)
        padded[1 + name.size] = 0x80.toByte()
        val key = key(historyKey)
        try {
            return B64.encode(Primitives.aesGcmSeal(key, nonce ?: SystemEntropy.bytes(Primitives.GCM_NONCE_BYTES), padded, aad(deviceId)))
        } finally {
            key.fill(0)
            padded.fill(0)
            name.fill(0)
        }
    }

    /**
     * Opens a sealed name; null when it does not open (`open`, `DeviceNameSeal.swift:68-85`): absent,
     * not strict Base64, not 156 bytes, another account's key, another device, tampered, or padding
     * that is not `0x80 0x00…` after a non-empty, valid UTF-8 name. An unknown kind byte is [Kind.Other].
     */
    fun open(sealed: String?, deviceId: UUID, historyKey: ByteArray): Label? {
        val combined = sealed?.let(B64::decodeStrict) ?: return null
        if (combined.size != SEALED_BYTES) return null
        val key = key(historyKey)
        val padded = try {
            Primitives.aesGcmOpen(key, combined, aad(deviceId))
        } catch (_: CryptoError) {
            return null
        } finally {
            key.fill(0)
        }
        try {
            val end = padded.indexOfLast { it.toInt() != 0 }
            if (end < 1 || padded[end] != 0x80.toByte()) return null
            val name = decodeUtf8Strict(padded, 1, end - 1) ?: return null
            if (name.isEmpty()) return null
            val first = padded[0].toInt() and 0xFF
            return Label(name = name, kind = Kind.fromByte(first and CUSTOM_BIT.inv()), custom = first and CUSTOM_BIT != 0)
        } finally {
            padded.fill(0)
        }
    }

    /**
     * One line, no control or bidi characters, at most [MAX_NAME_BYTES] UTF-8 bytes cut between
     * characters — an emoji is never split (`normalize`, `DeviceNameSeal.swift:87-106`; the web's
     * `normalizeDeviceName` follows the same rules):
     *
     * 1. Every control (general category Cc) and every line/paragraph separator or bidi mark and
     *    override (U+2028, U+2029, U+200E, U+200F, U+202A–U+202E, U+2066–U+2069) becomes a space.
     *    Joiners (U+200D) stay so emoji sequences survive.
     * 2. Split on whitespace (Swift `Character.isWhitespace` = Unicode White_Space, here
     *    `isWhitespace || isSpaceChar`) and join with single spaces.
     * 3. Append whole extended grapheme clusters (`java.text.BreakIterator`: ICU on Android,
     *    extended clusters on JDK 20+) while the total stays within [MAX_NAME_BYTES]; trim.
     */
    fun normalize(raw: String): String {
        val words = ArrayList<String>()
        val word = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            if (isStripped(cp) || isWhitespace(cp)) {
                if (word.isNotEmpty()) words += word.toString()
                word.setLength(0)
            } else {
                word.appendCodePoint(cp)
            }
        }
        if (word.isNotEmpty()) words += word.toString()
        val oneLine = words.joinToString(" ")

        val out = StringBuilder()
        var used = 0
        val clusters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(oneLine) }
        var start = clusters.first()
        var end = clusters.next()
        while (end != BreakIterator.DONE) {
            val cluster = oneLine.substring(start, end)
            val size = utf8(cluster).size
            if (used + size > MAX_NAME_BYTES) break
            out.append(cluster)
            used += size
            start = end
            end = clusters.next()
        }
        return out.toString().trim()
    }

    /** `isStripped` (`DeviceNameSeal.swift:108-116`). */
    private fun isStripped(cp: Int): Boolean = Character.getType(cp) == Character.CONTROL.toInt() || when (cp) {
        0x2028, 0x2029, 0x200E, 0x200F, in 0x202A..0x202E, in 0x2066..0x2069 -> true
        else -> false
    }

    private fun isWhitespace(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    /** `key` (`DeviceNameSeal.swift:118-125`). The caller zeroes it. */
    private fun key(historyKey: ByteArray): ByteArray = Primitives.hkdf(historyKey, SALT, utf8(LABEL), 32)

    /** `associatedData` (`DeviceNameSeal.swift:127-129`): the device id in its lower-case wire form. */
    private fun aad(deviceId: UUID): ByteArray = utf8("$LABEL:${Ids.wire(deviceId)}")

    /** Swift `String(bytes:encoding: .utf8)`: null on any malformed sequence (no replacement characters). */
    private fun decodeUtf8Strict(bytes: ByteArray, offset: Int, length: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, length))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}
