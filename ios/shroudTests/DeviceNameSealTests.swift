import CryptoKit
import Foundation
import Testing
@testable import shroud

struct DeviceNameSealTests {
    private let historyKey = SymmetricKey(data: Data((0 ..< 32).map { UInt8($0) }))
    private let deviceID = UUID(uuidString: "0F8FAD5B-D9CB-469F-A165-70867728950E")!

    /// Shared with web `src/crypto/deviceName.selftest.ts`. Change both or neither.
    private static let goldenLabel = DeviceNameSeal.Label(name: "Küchen-iPad ✨", kind: .iPad)
    private static let golden =
        "oKGio6SlpqeoqaqrdjG5oKGAC3ucvlABL0bwNia0p6bcRo/mVwqDA1t3ePUV3HzSP8S7pGiQrXYMyKXAkdmcxYeSpTsk7L+Z+mhJ86HjG+FTlWujHnID+iD9e5D7raMoy7sUVmTdpgn0GlH5boAtH0ueSvB5NtUt7/ZJpcfZ9Vq1mIGjrk81R9xO9X/DkcQzpugtYldAYowYWm3N"

    @Test
    func matchesTheWebClientByteForByte() throws {
        let nonce = try AES.GCM.Nonce(data: Data((0 ..< 12).map { UInt8(0xA0 + $0) }))
        let sealed = try DeviceNameSeal.seal(Self.goldenLabel, deviceID: deviceID, historyKey: historyKey, nonce: nonce)
        #expect(sealed == Self.golden)
        #expect(DeviceNameSeal.open(Self.golden, deviceID: deviceID, historyKey: historyKey) == Self.goldenLabel)
    }

    /// A rename: the kind byte carries the "typed by a person" bit. Shared with the web too.
    @Test
    func aRenameIsMarkedAsChosenLikeOnTheWeb() throws {
        let renamed = DeviceNameSeal.Label(name: "Office iPhone", kind: .iPhone, custom: true)
        let golden =
            "oKGio6Slpqeoqaqr9TUcequLCzXYh2gPJQOSqo40p6bcRo/mVwqDA1t3ePUV3HzSP8S7pGiQrXYMyKXAkdmcxYeSpTsk7L+Z+mhJ86HjG+FTlWujHnID+iD9e5D7raMoy7sUVmTdpgn0GlH5boAtH0ueSvB5NtUt7/ZJpcfZ9Vq1mIGjrk81R9xO9X+l2LSRw4GSvCdsnGiSSnet"
        let nonce = try AES.GCM.Nonce(data: Data((0 ..< 12).map { UInt8(0xA0 + $0) }))
        #expect(try DeviceNameSeal.seal(renamed, deviceID: deviceID, historyKey: historyKey, nonce: nonce) == golden)
        #expect(DeviceNameSeal.open(golden, deviceID: deviceID, historyKey: historyKey) == renamed)
    }

    /// Kind byte 4 is the Android app. Shared with Android (`DeviceNameSealTest`) and web
    /// (`deviceName.selftest.ts`); computed with the web client's libraries (android-port-specs
    /// crypto §16.3). Change all three or none.
    @Test
    func androidKindMatchesTheOtherClientsByteForByte() throws {
        let android = DeviceNameSeal.Label(name: "Pixel 9 Pro", kind: .android)
        let golden =
            "oKGio6SlpqeoqaqrcCoTZKeETiyRh3IPy2YSqo40p6bcRo/mVwqDA1t3ePUV3HzSP8S7pGiQrXYMyKXAkdmcxYeSpTsk7L+Z+mhJ86HjG+FTlWujHnID+iD9e5D7raMoy7sUVmTdpgn0GlH5boAtH0ueSvB5NtUt7/ZJpcfZ9Vq1mIGjrk81R9xO9X91Wb6trMGRUJzqvlJCgop+"
        let nonce = try AES.GCM.Nonce(data: Data((0 ..< 12).map { UInt8(0xA0 + $0) }))
        #expect(try DeviceNameSeal.seal(android, deviceID: deviceID, historyKey: historyKey, nonce: nonce) == golden)
        #expect(DeviceNameSeal.open(golden, deviceID: deviceID, historyKey: historyKey) == android)
        #expect(DeviceKind(label: android) == .android)
        #expect(DeviceKind(label: android).label == "Android app")
        #expect(DeviceKind(label: android).systemImage == "smartphone")
    }

    /// A rename made here keeps the Android kind and marks the name as chosen.
    @Test
    func aRenamedAndroidDeviceKeepsItsKind() throws {
        let renamed = DeviceNameSeal.Label(name: "Work phone", kind: .android, custom: true)
        let sealed = try DeviceNameSeal.seal(renamed, deviceID: deviceID, historyKey: historyKey)
        let opened = try #require(DeviceNameSeal.open(sealed, deviceID: deviceID, historyKey: historyKey))
        #expect(opened == renamed)
        #expect(opened.kind == .android && opened.custom)
    }

    /// A kind byte this build does not know (a newer client's) opens as `.other`, keeps the
    /// "chosen" bit, and the icon falls back to a guess from the name.
    @Test
    func anUnknownKindReadsAsOther() throws {
        var padded = Data(count: 128)
        let name = Data("Pixel 9 Pro".utf8)
        padded[0] = 9 | 0x80
        padded.replaceSubrange(1 ..< 1 + name.count, with: name)
        padded[1 + name.count] = 0x80
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: historyKey,
            salt: Data("shroud-v1".utf8),
            info: Data("shroud-device-name-v1".utf8),
            outputByteCount: 32
        )
        let aad = Data("shroud-device-name-v1:\(deviceID.uuidString.lowercased())".utf8)
        let box = try AES.GCM.seal(padded, using: key, authenticating: aad)
        let sealed = try #require(box.combined).base64EncodedString()

        let opened = DeviceNameSeal.open(sealed, deviceID: deviceID, historyKey: historyKey)
        #expect(opened == .init(name: "Pixel 9 Pro", kind: .other, custom: true))
        #expect(DeviceKind(label: opened) == .unknown)
        #expect(DeviceKind(label: .init(name: "Niklas’s Android phone", kind: .other)) == .android)
    }

    @Test
    func deviceKindsReadTheSealedKindFirst() {
        #expect(DeviceKind(label: .init(name: "Office", kind: .iPhone)) == .iPhone)
        #expect(DeviceKind(label: .init(name: "Office", kind: .iPad)) == .iPad)
        #expect(DeviceKind(label: .init(name: "Office", kind: .web)) == .browser)
        #expect(DeviceKind(label: .init(name: "Office", kind: .android)) == .android)
        // The kind wins over a misleading name.
        #expect(DeviceKind(label: .init(name: "My iPhone", kind: .android)) == .android)
        #expect(DeviceKind(label: nil) == .unknown)
    }

    @Test
    func everyNameSealsToOneSize() throws {
        let short = try DeviceNameSeal.seal(.init(name: "Mac", kind: .web), deviceID: deviceID, historyKey: historyKey)
        let long = try DeviceNameSeal.seal(
            .init(name: String(repeating: "x", count: 300), kind: .other),
            deviceID: deviceID,
            historyKey: historyKey
        )
        #expect(Data(base64Encoded: short)?.count == 156)
        #expect(Data(base64Encoded: long)?.count == 156)
        let opened = DeviceNameSeal.open(long, deviceID: deviceID, historyKey: historyKey)
        #expect(opened == .init(name: String(repeating: "x", count: DeviceNameSeal.maxNameBytes), kind: .other))
    }

    @Test
    func opensOnlyForItsDeviceAndAccount() throws {
        let sealed = try DeviceNameSeal.seal(.init(name: "iPhone 16 Pro", kind: .iPhone), deviceID: deviceID, historyKey: historyKey)
        let otherKey = SymmetricKey(data: Data((0 ..< 32).map { UInt8(255 - $0) }))
        #expect(DeviceNameSeal.open(sealed, deviceID: UUID(), historyKey: historyKey) == nil)
        #expect(DeviceNameSeal.open(sealed, deviceID: deviceID, historyKey: otherKey) == nil)

        var tampered = Data(base64Encoded: sealed)!
        tampered[20] ^= 1
        #expect(DeviceNameSeal.open(tampered.base64EncodedString(), deviceID: deviceID, historyKey: historyKey) == nil)
        #expect(DeviceNameSeal.open(nil, deviceID: deviceID, historyKey: historyKey) == nil)
        #expect(DeviceNameSeal.open("not base64", deviceID: deviceID, historyKey: historyKey) == nil)
    }

    @Test
    func normalizesLikeTheWebClient() {
        #expect(DeviceNameSeal.normalize("  Work\n\tlaptop \u{7} ") == "Work laptop")
        #expect(DeviceNameSeal.normalize(String(repeating: "🌙", count: 40)) == String(repeating: "🌙", count: 24))
        #expect(DeviceNameSeal.normalize(" \n ") == "")
        #expect(DeviceNameSeal.normalize("Mac\u{202E}gnp.exe") == "Mac gnp.exe")
        #expect(DeviceNameSeal.normalize("👩\u{200D}💻 laptop") == "👩\u{200D}💻 laptop")
        #expect(throws: DeviceNameSeal.SealError.emptyName) {
            try DeviceNameSeal.seal(.init(name: "   ", kind: .iPhone), deviceID: deviceID, historyKey: historyKey)
        }
    }

    @Test
    func knownModelsHaveMarketingNames() {
        #expect(DeviceModelName.marketingName(identifier: "iPhone17,1") == "iPhone 16 Pro")
        #expect(DeviceModelName.marketingName(identifier: "iPhone99,9") == nil)
    }
}
