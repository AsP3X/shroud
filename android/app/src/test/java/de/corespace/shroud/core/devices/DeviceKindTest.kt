package de.corespace.shroud.core.devices

import de.corespace.shroud.core.crypto.DeviceNameSeal
import de.corespace.shroud.core.crypto.DeviceNameSeal.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every row of settings-lock §4.5 (iOS `DeviceKind`, `DevicesView.swift:794-857`; web
 * `deviceKind`, `DeviceTile.tsx:13-38`).
 */
class DeviceKindTest {
    private fun kind(name: String, kind: Kind = Kind.Other, custom: Boolean = false) =
        DeviceKind.of(DeviceNameSeal.Label(name, kind, custom))

    @Test
    fun theSealedKindWins() {
        assertEquals(DeviceKind.IPhone, kind("Office", Kind.IPhone))
        assertEquals(DeviceKind.IPad, kind("Küchen-iPad ✨", Kind.IPad))
        assertEquals(DeviceKind.Web, kind("Firefox on Linux", Kind.Web))
        assertEquals(DeviceKind.Android, kind("Pixel 9 Pro", Kind.Android))
        // A typed name keeps its sealed kind; the name does not override it.
        assertEquals(DeviceKind.Android, kind("My iPhone replacement", Kind.Android, custom = true))
        assertEquals(DeviceKind.IPhone, kind("Chrome on Mac", Kind.IPhone))
    }

    @Test
    fun withoutAKindTheNameIsGuessed() {
        assertEquals(DeviceKind.IPhone, kind("Niklas’s iPhone"))
        assertEquals(DeviceKind.IPad, kind("iPad Pro"))
        assertEquals(DeviceKind.Phone, kind("Galaxy phone"))
        assertEquals(DeviceKind.Phone, kind("Android tablet"))
        // "iPhone" contains "phone", but the iPhone rule comes first.
        assertEquals(DeviceKind.IPhone, kind("IPHONE 16"))
        assertEquals(DeviceKind.Web, kind("Chrome on Mac"))
        assertEquals(DeviceKind.Web, kind("Safari"))
        assertEquals(DeviceKind.Web, kind("Edge"))
        assertEquals(DeviceKind.Web, kind("Work browser"))
        assertEquals(DeviceKind.Mac, kind("MacBook Air"))
        assertEquals(DeviceKind.Pc, kind("Windows laptop"))
        assertEquals(DeviceKind.Pc, kind("linux box"))
        assertEquals(DeviceKind.Unknown, kind("Pixel 9a"))
        assertEquals(DeviceKind.Unknown, DeviceKind.of(null))
    }

    @Test
    fun labelsTintsAndGlyphs() {
        assertEquals("iPhone app", DeviceKind.IPhone.label)
        assertEquals("iPad app", DeviceKind.IPad.label)
        assertEquals("Android app", DeviceKind.Android.label)
        assertEquals("Phone", DeviceKind.Phone.label)
        assertEquals("Web browser", DeviceKind.Web.label)
        assertEquals("Mac", DeviceKind.Mac.label)
        assertEquals("Computer", DeviceKind.Pc.label)
        assertEquals("Unknown", DeviceKind.Unknown.label)

        assertEquals(0xFF2E8FE0.toInt(), DeviceKind.IPhone.tintArgb)
        assertEquals(0xFF2E8FE0.toInt(), DeviceKind.IPad.tintArgb)
        // Green for Android (P4), not the design's blue.
        assertEquals(0xFF2FA85B.toInt(), DeviceKind.Android.tintArgb)
        assertEquals(0xFF2FA85B.toInt(), DeviceKind.Phone.tintArgb)
        assertEquals(0xFFF76B1C.toInt(), DeviceKind.Web.tintArgb)
        assertEquals(0xFF9B4AE6.toInt(), DeviceKind.Mac.tintArgb)
        assertEquals(0xFF9B4AE6.toInt(), DeviceKind.Pc.tintArgb)
        assertNull(DeviceKind.Unknown.tintArgb)

        assertEquals(DeviceKind.Glyph.Phone, DeviceKind.Android.glyph)
        assertEquals(DeviceKind.Glyph.Tablet, DeviceKind.IPad.glyph)
        assertEquals(DeviceKind.Glyph.Globe, DeviceKind.Web.glyph)
        assertEquals(DeviceKind.Glyph.Laptop, DeviceKind.Mac.glyph)
        assertEquals(DeviceKind.Glyph.Desktop, DeviceKind.Unknown.glyph)
    }

    @Test
    fun theNounFollowsTheSmallestWidth() {
        assertEquals("phone", DeviceNoun.forSmallestWidthDp(360))
        assertEquals("phone", DeviceNoun.forSmallestWidthDp(599))
        assertEquals("tablet", DeviceNoun.forSmallestWidthDp(600))
        assertEquals("tablet", DeviceNoun.forSmallestWidthDp(800))
    }
}
