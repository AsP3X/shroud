package de.corespace.shroud.core.devices

import de.corespace.shroud.core.crypto.DeviceNameSeal

/**
 * What a linked device is, for its row in Settings › Devices: the kind sealed with its name, else a
 * guess from the name (iOS `DeviceKind`, `ios/shroud/Features/Main/DevicesView.swift:794-857`; web
 * `deviceKind`, `web/src/components/DeviceTile.tsx:13-38`; settings-lock §4.5).
 *
 * The sealed kind byte 4 reads "Android app" (00-plan P4). A name that only *looks* like a phone
 * keeps the guess [Phone] ("Phone"): it may be an Android device renamed by an older client that
 * dropped its kind (`DevicesView.swift:795, 810-811, 828`).
 *
 * Plain values only: the UI (W3-SETTINGS-B) maps [glyph] to its icon and draws [tintArgb] (null =
 * the `textSecondary` token).
 *
 * @property label the Type row of Device Details.
 * @property tintArgb the tile's fill, opaque ARGB; null = `textSecondary`.
 * @property glyph the tile's icon (settings-lock §4.5 Android icon column).
 */
enum class DeviceKind(val label: String, val tintArgb: Int?, val glyph: Glyph) {
    IPhone("iPhone app", BLUE, Glyph.Phone),
    IPad("iPad app", BLUE, Glyph.Tablet),

    /** Sealed kind 4: Shroud for Android (P4). */
    Android("Android app", GREEN, Glyph.Phone),

    /** No sealed kind, but the name says "android" or "phone" (iOS `.phone`). */
    Phone("Phone", GREEN, Glyph.Phone),
    Web("Web browser", ORANGE, Glyph.Globe),
    Mac("Mac", PURPLE, Glyph.Laptop),
    Pc("Computer", PURPLE, Glyph.Desktop),
    Unknown("Unknown", null, Glyph.Desktop),
    ;

    /** The tile's icon: Phosphor `device-mobile-fill`, `device-tablet-fill`, `laptop-fill`, `desktop-fill`; Lucide `globe`. */
    enum class Glyph { Phone, Tablet, Globe, Laptop, Desktop }

    companion object {
        /** `DeviceKind(label:)` (`DevicesView.swift:797-821`): the sealed kind wins; kind 0 or no label guesses from the name. */
        fun of(label: DeviceNameSeal.Label?): DeviceKind {
            when (label?.kind) {
                DeviceNameSeal.Kind.IPhone -> return IPhone
                DeviceNameSeal.Kind.IPad -> return IPad
                DeviceNameSeal.Kind.Web -> return Web
                DeviceNameSeal.Kind.Android -> return Android
                DeviceNameSeal.Kind.Other, null -> Unit
            }
            val lower = (label?.name ?: "").lowercase()
            return when {
                "iphone" in lower -> IPhone
                "ipad" in lower -> IPad
                "android" in lower || "phone" in lower -> Phone
                BROWSER_WORDS.any { it in lower } -> Web
                "mac" in lower -> Mac
                "windows" in lower || "linux" in lower -> Pc
                else -> Unknown
            }
        }

        private val BROWSER_WORDS = listOf("chrome", "safari", "firefox", "edge", "browser", " on ")
    }
}

// DevicesView.swift:848-856.
private const val BLUE = 0xFF2E8FE0.toInt()
private const val GREEN = 0xFF2FA85B.toInt()
private const val ORANGE = 0xFFF76B1C.toInt()
private const val PURPLE = 0xFF9B4AE6.toInt()
