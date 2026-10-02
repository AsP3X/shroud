package de.corespace.shroud.ui.settings

import de.corespace.shroud.core.appearance.BrandLogoStyle
import de.corespace.shroud.core.appearance.ColorTheme
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.core.notifications.NotificationAuthorization
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Settings root's name, handle, initials and row values (settings-lock §3.3-3.4, §18.3
 * `DisplayNameTest`), and the Appearance copy (§8.1), against `SettingsView.swift:155-189` and
 * `AppearanceSettingsView.swift:24-30, 161-176`.
 */
class SettingsCopyTest {
    @Test
    fun displayNameFromTheUsername() {
        assertEquals("Niklas V", SettingsIdentity.displayName("niklas_v"))
        assertEquals("Noah Vorberg", SettingsIdentity.displayName("NOAH_vorberg"))
        assertEquals("Alice", SettingsIdentity.displayName("alice"))
        // Swift's split(separator:) drops empty pieces, so runs of "_" collapse.
        assertEquals("A B", SettingsIdentity.displayName("a__b"))
        assertEquals("Mcdonald", SettingsIdentity.displayName("McDonald"))
        // The first character is a grapheme cluster, upper-cased without locale rules.
        assertEquals("Élodie", SettingsIdentity.displayName("élodie").let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC) })
        assertEquals("Istanbul", SettingsIdentity.displayName("istanbul"))
    }

    /**
     * Name, handle and hero initials against the iOS code itself: `SettingsView.swift:155-175` and
     * `AvatarView.swift:67-79` copied verbatim into a Swift script and run with Apple Swift 6.4 on
     * 2026-10-03. Swift's `uppercased()` / `lowercased()` are full, locale-free case mappings (ß → SS,
     * ﬁ → FI, ǆ → Ǆ) and `prefix(1)` is one grapheme cluster (a decomposed é, a skin-toned emoji).
     */
    @Test
    fun displayNameHandleAndInitialsMatchSwift() {
        val swift = listOf(
            // input, display name, handle, initials
            listOf(null, "Shroud User", "@user", "SU"),
            listOf("", "Shroud User", "@user", "SU"),
            listOf("niklas_v", "Niklas V", "@niklas_v", "NV"),
            listOf("NOAH_vorberg", "Noah Vorberg", "@NOAH_vorberg", "NV"),
            listOf("alice", "Alice", "@alice", "AL"),
            listOf("a__b", "A B", "@a__b", "AB"),
            listOf("McDonald", "Mcdonald", "@McDonald", "MC"),
            listOf("_lead_", "Lead", "@_lead_", "LE"),
            listOf("___", "", "@___", "?"),
            listOf("élodie", "Élodie", "@élodie", "ÉL"),
            listOf("élodie", "Élodie", "@élodie", "ÉL"),
            listOf("istanbul", "Istanbul", "@istanbul", "IS"),
            listOf("ǆemal", "Ǆemal", "@ǆemal", "ǄE"),
            listOf("ßtraße", "SStraße", "@ßtraße", "SS"),
            listOf("o'brien", "O'brien", "@o'brien", "O'"),
            listOf("anne-marie", "Anne-marie", "@anne-marie", "AN"),
            listOf("x", "X", "@x", "X"),
            listOf("émile_zola", "Émile Zola", "@émile_zola", "ÉZ"),
            listOf("日本_太郎", "日本 太郎", "@日本_太郎", "日太"),
            listOf("👍🏽cool", "👍🏽cool", "@👍🏽cool", "👍🏽C"),
            listOf("dž_x", "Dž X", "@dž_x", "DX"),
            listOf("ﬁnn", "FInn", "@ﬁnn", "FI"),
        )
        for ((input, name, handle, initials) in swift) {
            val shown = SettingsIdentity.displayName(input)
            assertEquals("name of $input", name, shown)
            assertEquals("handle of $input", handle, SettingsIdentity.handle(input))
            assertEquals("initials of $input", initials, SettingsIdentity.initials(shown))
        }
    }

    @Test
    fun noUsernameIsShroudUser() {
        assertEquals("Shroud User", SettingsIdentity.displayName(null))
        assertEquals("Shroud User", SettingsIdentity.displayName(""))
        // Only underscores: a username, but no words (iOS shows the empty result too).
        assertEquals("", SettingsIdentity.displayName("___"))
    }

    @Test
    fun handle() {
        assertEquals("@niklas_v", SettingsIdentity.handle("niklas_v"))
        assertEquals("@user", SettingsIdentity.handle(null))
        assertEquals("@user", SettingsIdentity.handle(""))
    }

    @Test
    fun initialsFollowAvatarView() {
        assertEquals("NV", SettingsIdentity.initials("Niklas V"))
        assertEquals("SU", SettingsIdentity.initials("Shroud User"))
        assertEquals("AL", SettingsIdentity.initials("Alice"))
        assertEquals("?", SettingsIdentity.initials(""))
    }

    @Test
    fun notificationsSummary() {
        assertEquals("On", SettingsCopy.notificationsSummary(NotificationAuthorization.Authorized, enabled = true))
        assertEquals("On", SettingsCopy.notificationsSummary(NotificationAuthorization.NotDetermined, enabled = true))
        assertEquals("Off", SettingsCopy.notificationsSummary(NotificationAuthorization.Denied, enabled = true))
        assertEquals("Off", SettingsCopy.notificationsSummary(NotificationAuthorization.Authorized, enabled = false))
    }

    @Test
    fun serverSubtitle() {
        assertEquals("Official · api.shroud.app", SettingsCopy.serverSubtitle(ServerConfiguration.official))
        assertEquals("http://10.0.2.2:8080/api/v1", SettingsCopy.serverSubtitle(ServerConfiguration.localDevelopment("10.0.2.2", 8080)))
        val selfHosted = ServerConfiguration(ServerConnectionMode.SelfHosted, "chat.example.org", "", "/api/v1/", true)
        assertEquals("https://chat.example.org/api/v1", SettingsCopy.serverSubtitle(selfHosted))
    }

    @Test
    fun userIdIsShownLowerCase() {
        assertEquals("3f2504e0-4f89-41d3-9a0c-0305e82c3301", SettingsCopy.userIdLine("3F2504E0-4F89-41D3-9A0C-0305E82C3301"))
        assertEquals("not-a-uuid", SettingsCopy.userIdLine("NOT-A-UUID"))
    }

    @Test
    fun logOutMessageNamesThisDevice() {
        assertEquals(
            "Everything Shroud keeps on this phone is deleted: messages, photos and voice notes, your encryption keys and settings. " +
                "Your other devices keep your chats. To sign in again you’ll need your password and encryption phrase.",
            SettingsCopy.logOutMessage(DeviceNoun.PHONE),
        )
        assertEquals(true, SettingsCopy.logOutMessage(DeviceNoun.TABLET).startsWith("Everything Shroud keeps on this tablet is deleted"))
        assertEquals("Log out of Shroud?", SettingsCopy.LOG_OUT_TITLE)
        assertEquals("Signing out…", SettingsCopy.SIGNING_OUT)
    }

    @Test
    fun appearanceCopy() {
        assertEquals(
            "System follows your phone’s light or dark setting. The choice applies to this phone only.",
            AppearanceCopy.themeFooter(DeviceNoun.PHONE),
        )
        assertEquals("The logo on your home screen and inside the app.", AppearanceCopy.LOGO_FOOTER)
        assertEquals("The app icon couldn’t be changed. Try again.", AppearanceCopy.LOGO_FAILURE)
        assertEquals(listOf("System", "Light", "Dark"), ColorTheme.entries.map { it.title })
        assertEquals(
            listOf("Detailed" to "The veil with its folds and shading.", "Simple" to "One flat shape."),
            BrandLogoStyle.entries.map { it.title to it.subtitle },
        )
    }
}
