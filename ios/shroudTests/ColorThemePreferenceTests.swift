import Foundation
import Testing
import UIKit
@testable import shroud

/// The colour theme from Settings › Appearance: stored per iPhone, cleared by a logout.
@MainActor
struct ColorThemePreferenceTests {
    private func freshDefaults() throws -> UserDefaults {
        let name = "color-theme-tests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: name))
        defaults.removePersistentDomain(forName: name)
        return defaults
    }

    @Test func startsOnSystem() throws {
        let preference = ColorThemePreference(defaults: try freshDefaults())
        #expect(preference.theme == .system)
    }

    @Test func aChoiceSurvivesARelaunch() throws {
        let defaults = try freshDefaults()
        ColorThemePreference(defaults: defaults).choose(.dark)
        #expect(ColorThemePreference(defaults: defaults).theme == .dark)
        // The web client's key and values, so the two read alike.
        #expect(defaults.string(forKey: "shroud.theme") == "dark")
    }

    @Test func systemStoresNothing() throws {
        let defaults = try freshDefaults()
        let preference = ColorThemePreference(defaults: defaults)
        preference.choose(.light)
        preference.choose(.system)
        #expect(defaults.object(forKey: ColorThemePreference.defaultsKey) == nil)
    }

    @Test func forgetGoesBackToSystem() throws {
        let defaults = try freshDefaults()
        let preference = ColorThemePreference(defaults: defaults)
        preference.choose(.dark)
        preference.forget()
        #expect(preference.theme == .system)
        #expect(defaults.object(forKey: ColorThemePreference.defaultsKey) == nil)
    }

    @Test func anUnknownValueFallsBackToSystem() throws {
        let defaults = try freshDefaults()
        defaults.set("sepia", forKey: ColorThemePreference.defaultsKey)
        #expect(ColorThemePreference(defaults: defaults).theme == .system)
    }

    @Test func aLogoutClearsIt() {
        #expect(!DeviceDataWipe.keepsDefaultsKey(ColorThemePreference.defaultsKey))
    }

    @Test func mapsToTheWindowStyle() {
        #expect(ColorTheme.system.userInterfaceStyle == .unspecified)
        #expect(ColorTheme.light.userInterfaceStyle == .light)
        #expect(ColorTheme.dark.userInterfaceStyle == .dark)
    }
}
