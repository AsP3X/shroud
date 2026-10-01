package de.corespace.shroud.core.crypto

import de.corespace.shroud.core.crypto.CryptoFixtures.sequence
import de.corespace.shroud.core.crypto.DeviceNameSeal.Kind
import de.corespace.shroud.core.crypto.DeviceNameSeal.Label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

/**
 * Sealed device names (iOS `ios/shroudTests/DeviceNameSealTests.swift`, web
 * `web/src/crypto/deviceName.selftest.ts`; crypto spec §17.2). The two goldens are shared with iOS
 * and web ("change both or neither"); the kind-4 golden is Android's (crypto D4, plan P4), printed by
 * `gen_message_crypto_vectors.mjs` next to this file and handed to X1-IOS / X1-WEB.
 */
class DeviceNameSealTest {
    /** `DeviceNameSealTests.swift:7-8`: bytes 0x00…0x1f. */
    private val historyKey = sequence(0x00, 32)
    private val deviceId: UUID = UUID.fromString("0F8FAD5B-D9CB-469F-A165-70867728950E")

    /** `DeviceNameSealTests.swift:17`: 0xA0…0xAB. */
    private val nonce = sequence(0xA0, 12)

    /** `DeviceNameSealTests.swift:15-21`. */
    @Test
    fun matchesTheWebClientByteForByte() {
        val label = Label(name = "Küchen-iPad ✨", kind = Kind.IPad)
        assertEquals(GOLDEN, DeviceNameSeal.seal(label, deviceId, historyKey, nonce))
        assertEquals(label, DeviceNameSeal.open(GOLDEN, deviceId, historyKey))
    }

    /** `DeviceNameSealTests.swift:23-32`: a rename carries the "typed by a person" bit. */
    @Test
    fun aRenameIsMarkedAsChosenLikeOnTheWeb() {
        val renamed = Label(name = "Office iPhone", kind = Kind.IPhone, custom = true)
        assertEquals(GOLDEN_RENAMED, DeviceNameSeal.seal(renamed, deviceId, historyKey, nonce))
        assertEquals(renamed, DeviceNameSeal.open(GOLDEN_RENAMED, deviceId, historyKey))
    }

    /** Crypto spec §16.3, decision D4 / P4: kind byte 4 = "Android app", same key, nonce and device id. */
    @Test
    fun androidKindFourMatchesTheVector() {
        val pixel = Label(name = "Pixel 9 Pro", kind = Kind.Android)
        assertEquals(GOLDEN_ANDROID, DeviceNameSeal.seal(pixel, deviceId, historyKey, nonce))
        assertEquals(pixel, DeviceNameSeal.open(GOLDEN_ANDROID, deviceId, historyKey))
        assertEquals(4, Kind.Android.byte)
        // The name key of crypto spec §16.3 (the HKDF row both other clients share).
        assertEquals(
            "aaf7dc6f151ce2c56310f3621370a17c3bdf6a584790bd4ad8275905098e7dd8",
            Primitives.hkdf(historyKey, utf8("shroud-v1"), utf8("shroud-device-name-v1"), 32).hex(),
        )
    }

    /** `DeviceNameSealTests.swift:34-46`. */
    @Test
    fun everyNameSealsToOneSize() {
        val short = DeviceNameSeal.seal(Label("Mac", Kind.Web), deviceId, historyKey)
        val long = DeviceNameSeal.seal(Label("x".repeat(300), Kind.Other), deviceId, historyKey)
        assertEquals(156, B64.decodeStrict(short)!!.size)
        assertEquals(156, B64.decodeStrict(long)!!.size)
        assertEquals(208, short.length)
        assertEquals(Label("x".repeat(DeviceNameSeal.MAX_NAME_BYTES), Kind.Other), DeviceNameSeal.open(long, deviceId, historyKey))
        // web `deviceName.selftest.ts:44`: the kind round-trips.
        assertEquals(Kind.Web, DeviceNameSeal.open(short, deviceId, historyKey)!!.kind)
    }

    /** `DeviceNameSealTests.swift:48-60`. */
    @Test
    fun opensOnlyForItsDeviceAndAccount() {
        val sealed = DeviceNameSeal.seal(Label("iPhone 16 Pro", Kind.IPhone), deviceId, historyKey)
        val otherKey = ByteArray(32) { (255 - it).toByte() }
        assertNull(DeviceNameSeal.open(sealed, UUID.randomUUID(), historyKey))
        assertNull(DeviceNameSeal.open(sealed, deviceId, otherKey))

        val tampered = B64.decodeStrict(sealed)!!
        tampered[20] = (tampered[20].toInt() xor 1).toByte()
        assertNull(DeviceNameSeal.open(B64.encode(tampered), deviceId, historyKey))
        assertNull(DeviceNameSeal.open(null, deviceId, historyKey))
        assertNull(DeviceNameSeal.open("not base64", deviceId, historyKey))
    }

    /** `DeviceNameSealTests.swift:62-72`. */
    @Test
    fun normalizesLikeTheWebClient() {
        assertEquals("Work laptop", DeviceNameSeal.normalize("  Work\n\tlaptop \u0007 "))
        assertEquals("🌙".repeat(24), DeviceNameSeal.normalize("🌙".repeat(40)))
        assertEquals("", DeviceNameSeal.normalize(" \n "))
        assertEquals("Mac gnp.exe", DeviceNameSeal.normalize("Mac‮gnp.exe"))
        assertEquals("👩‍💻 laptop", DeviceNameSeal.normalize("👩‍💻 laptop"))
        assertThrows(DeviceNameSeal.SealError.EmptyName::class.java) {
            DeviceNameSeal.seal(Label("   ", Kind.IPhone), deviceId, historyKey)
        }
    }

    /** web `deviceName.selftest.ts:36`: the device id's case does not matter (the AAD is lower-case). */
    @Test
    fun theDeviceIdCaseDoesNotMatter() {
        val lower = UUID.fromString(deviceId.toString().lowercase())
        assertEquals("Küchen-iPad ✨", DeviceNameSeal.open(GOLDEN, lower, historyKey)!!.name)
    }

    /** web `deviceName.selftest.ts:48`: two seals of one name differ (a fresh nonce each time). */
    @Test
    fun everySealHasAFreshNonce() {
        val first = DeviceNameSeal.seal(Label("Mac", Kind.Web), deviceId, historyKey)
        val second = DeviceNameSeal.seal(Label("Mac", Kind.Web), deviceId, historyKey)
        assertNotEquals(first, second)
    }

    /** `DeviceNameSeal.swift:69-85`: the padding must be `0x80 0x00…` after a non-empty, valid UTF-8 name; unknown kinds read as other. */
    @Test
    fun paddingAndKindRulesOfOpen() {
        val key = Primitives.hkdf(historyKey, utf8("shroud-v1"), utf8("shroud-device-name-v1"), 32)
        val aad = utf8("shroud-device-name-v1:" + deviceId.toString())
        fun sealPadded(padded: ByteArray) = B64.encode(Primitives.aesGcmSeal(key, nonce, padded, aad))
        fun padded(first: Int, body: ByteArray, marker: Int? = 0x80) = ByteArray(128).also {
            it[0] = first.toByte()
            body.copyInto(it, 1)
            if (marker != null) it[1 + body.size] = marker.toByte()
        }
        // Kind 9 is unknown → other; the custom bit survives.
        assertEquals(Label("Box", Kind.Other, custom = true), DeviceNameSeal.open(sealPadded(padded(0x89, utf8("Box"))), deviceId, historyKey))
        // No 0x80 marker after the name.
        assertNull(DeviceNameSeal.open(sealPadded(padded(1, utf8("Box"), marker = null)), deviceId, historyKey))
        // An empty name (the marker right after the kind byte).
        assertNull(DeviceNameSeal.open(sealPadded(padded(1, ByteArray(0))), deviceId, historyKey))
        // All zero: no marker at all.
        assertNull(DeviceNameSeal.open(sealPadded(ByteArray(128)), deviceId, historyKey))
        // Malformed UTF-8 is refused, never replaced (Swift `String(bytes:encoding:)`).
        assertNull(DeviceNameSeal.open(sealPadded(padded(1, byteArrayOf(0x41, 0xC3.toByte(), 0x28))), deviceId, historyKey))
        // A blob of another size never reaches GCM.
        assertNull(DeviceNameSeal.open(B64.encode(Primitives.aesGcmSeal(key, nonce, ByteArray(127), aad)), deviceId, historyKey))
    }

    /** Names never reach logs: the label's `toString` leaves the name out. */
    @Test
    fun aLabelNeverPrintsItsName() {
        assertFalse(Label("Niklas's Pixel", Kind.Android).toString().contains("Niklas"))
    }

    private companion object {
        /** `ios/shroudTests/DeviceNameSealTests.swift:12-13` = `web/src/crypto/deviceName.selftest.ts:22-24`. */
        const val GOLDEN =
            "oKGio6SlpqeoqaqrdjG5oKGAC3ucvlABL0bwNia0p6bcRo/mVwqDA1t3ePUV3HzSP8S7pGiQrXYMyKXAkdmcxYeSpTsk7L+Z+mhJ86HjG+FTlWujHnID+iD9e5D7raMoy7sUVmTdpgn0GlH5boAtH0ueSvB5NtUt7/ZJpcfZ9Vq1mIGjrk81R9xO9X/DkcQzpugtYldAYowYWm3N"

        /** `ios/shroudTests/DeviceNameSealTests.swift:27-28` = `web/src/crypto/deviceName.selftest.ts:27-28`. */
        const val GOLDEN_RENAMED =
            "oKGio6Slpqeoqaqr9TUcequLCzXYh2gPJQOSqo40p6bcRo/mVwqDA1t3ePUV3HzSP8S7pGiQrXYMyKXAkdmcxYeSpTsk7L+Z+mhJ86HjG+FTlWujHnID+iD9e5D7raMoy7sUVmTdpgn0GlH5boAtH0ueSvB5NtUt7/ZJpcfZ9Vq1mIGjrk81R9xO9X+l2LSRw4GSvCdsnGiSSnet"

        /** Crypto spec §16.3 ("Pixel 9 Pro", kind 4), from `gen_message_crypto_vectors.mjs`. */
        const val GOLDEN_ANDROID =
            "oKGio6SlpqeoqaqrcCoTZKeETiyRh3IPy2YSqo40p6bcRo/mVwqDA1t3ePUV3HzSP8S7pGiQrXYMyKXAkdmcxYeSpTsk7L+Z+mhJ86HjG+FTlWujHnID+iD9e5D7raMoy7sUVmTdpgn0GlH5boAtH0ueSvB5NtUt7/ZJpcfZ9Vq1mIGjrk81R9xO9X91Wb6trMGRUJzqvlJCgop+"
    }
}
