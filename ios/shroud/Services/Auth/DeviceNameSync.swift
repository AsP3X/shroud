import CryptoKit
import Foundation
import UIKit

/// Keeps this iPhone's sealed name on the server in step with its real name.
///
/// Human: The iPhone app doesn't ask for a name; it uses the one the phone already has. The
/// name only leaves the phone sealed (`DeviceNameSeal`), after an unlock, because the key
/// comes from the phrase. One `/auth/me` per unlock; a write only when the name changed. A name
/// someone chose (Settings → Devices, here or on the web) is kept: the phone stops syncing its own.
/// Agent: CALLS AuthService.fetchMe + DevicesService.putName; READS UIDevice; never logs the name.
@MainActor
enum DeviceNameSync {
    private static var inFlight = false

    /// Best effort: offline or a server error leaves it for the next unlock.
    static func syncIfNeeded(session: SessionStore.Session, historyKey: SymmetricKey) async {
        guard !inFlight else { return }
        inFlight = true
        defer { inFlight = false }

        let wanted = currentLabel()
        do {
            let me = try await AuthService().fetchMe(session: session)
            guard me.device.id == session.deviceID else { return }
            let stored = DeviceNameSeal.open(me.device.sealedName, deviceID: session.deviceID, historyKey: historyKey)
            if stored == wanted || stored?.custom == true { return }
            let sealed = try DeviceNameSeal.seal(wanted, deviceID: session.deviceID, historyKey: historyKey)
            try await DevicesService().putName(deviceID: session.deviceID, sealedName: sealed, token: session.token)
        } catch {
            // Next unlock tries again.
        }
    }

    /// The phone's own name ("Niklas's iPhone") when iOS hands it out, else its model
    /// ("iPhone 16 Pro"). Since iOS 16, `UIDevice.name` is the generic "iPhone" unless the app
    /// holds Apple's user-assigned-device-name entitlement.
    static func currentLabel() -> DeviceNameSeal.Label {
        let device = UIDevice.current
        let kind: DeviceNameSeal.Kind = device.userInterfaceIdiom == .pad ? .iPad : .iPhone
        let assigned = DeviceNameSeal.normalize(device.name)
        let generic = assigned.isEmpty || assigned == device.model
        let name = generic ? (DeviceModelName.marketingName() ?? device.model) : assigned
        return DeviceNameSeal.Label(name: DeviceNameSeal.normalize(name), kind: kind)
    }
}

/// "iPhone17,1" → "iPhone 16 Pro". Unknown (newer) models give nil and fall back to "iPhone".
nonisolated enum DeviceModelName {
    static func marketingName(identifier: String = hardwareIdentifier()) -> String? {
        names[identifier]
    }

    static func hardwareIdentifier() -> String {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] {
            return simulated
        }
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }

    private static let names: [String: String] = [
        "iPhone12,1": "iPhone 11",
        "iPhone12,3": "iPhone 11 Pro",
        "iPhone12,5": "iPhone 11 Pro Max",
        "iPhone12,8": "iPhone SE (2nd generation)",
        "iPhone13,1": "iPhone 12 mini",
        "iPhone13,2": "iPhone 12",
        "iPhone13,3": "iPhone 12 Pro",
        "iPhone13,4": "iPhone 12 Pro Max",
        "iPhone14,2": "iPhone 13 Pro",
        "iPhone14,3": "iPhone 13 Pro Max",
        "iPhone14,4": "iPhone 13 mini",
        "iPhone14,5": "iPhone 13",
        "iPhone14,6": "iPhone SE (3rd generation)",
        "iPhone14,7": "iPhone 14",
        "iPhone14,8": "iPhone 14 Plus",
        "iPhone15,2": "iPhone 14 Pro",
        "iPhone15,3": "iPhone 14 Pro Max",
        "iPhone15,4": "iPhone 15",
        "iPhone15,5": "iPhone 15 Plus",
        "iPhone16,1": "iPhone 15 Pro",
        "iPhone16,2": "iPhone 15 Pro Max",
        "iPhone17,1": "iPhone 16 Pro",
        "iPhone17,2": "iPhone 16 Pro Max",
        "iPhone17,3": "iPhone 16",
        "iPhone17,4": "iPhone 16 Plus",
        "iPhone17,5": "iPhone 16e",
        "iPhone18,1": "iPhone 17 Pro",
        "iPhone18,2": "iPhone 17 Pro Max",
        "iPhone18,3": "iPhone 17",
        "iPhone18,4": "iPhone Air",
    ]
}
