import SwiftUI

/// Circular initials avatar — maps to reusable `Avatar` in `iOS-App.pen`.
struct AvatarView: View {
    let initials: String
    var size: CGFloat = 52
    var gradient: LinearGradient = Theme.brandGradient
    var fontSize: CGFloat?

    private var resolvedFontSize: CGFloat {
        fontSize ?? max(12, size * 0.36)
    }

    var body: some View {
        Text(initials)
            .font(.system(size: resolvedFontSize, weight: .semibold))
            .foregroundStyle(Color.white)
            .frame(width: size, height: size)
            .background(gradient)
            .clipShape(Circle())
            .accessibilityHidden(true)
    }
}

extension AvatarView {
    /// Top-to-bottom pairs, in the same order as `PALETTE` in the web client's
    /// `web/src/components/Avatar.tsx`: an index names the same colours on both.
    private static let palette: [(Color, Color)] = [
        (Color(red: 124 / 255, green: 122 / 255, blue: 255 / 255), Color(red: 94 / 255, green: 92 / 255, blue: 230 / 255)),
        (Color(red: 255 / 255, green: 159 / 255, blue: 90 / 255), Color(red: 247 / 255, green: 107 / 255, blue: 28 / 255)),
        (Color(red: 255 / 255, green: 122 / 255, blue: 158 / 255), Color(red: 230 / 255, green: 74 / 255, blue: 114 / 255)),
        (Color(red: 74 / 255, green: 199 / 255, blue: 250 / 255), Color(red: 46 / 255, green: 143 / 255, blue: 224 / 255)),
        (Color(red: 90 / 255, green: 217 / 255, blue: 124 / 255), Color(red: 47 / 255, green: 168 / 255, blue: 91 / 255)),
        (Color(red: 199 / 255, green: 124 / 255, blue: 255 / 255), Color(red: 155 / 255, green: 74 / 255, blue: 230 / 255)),
        (Color(red: 255 / 255, green: 198 / 255, blue: 90 / 255), Color(red: 230 / 255, green: 154 / 255, blue: 28 / 255)),
        (Color(red: 142 / 255, green: 142 / 255, blue: 147 / 255), Color(red: 95 / 255, green: 95 / 255, blue: 102 / 255)),
    ]

    /// Deterministic accent gradient from a display name (stable per contact).
    static func gradient(for name: String) -> LinearGradient {
        let pair = palette[paletteIndex(for: name)]
        return LinearGradient(colors: [pair.0, pair.1], startPoint: .top, endPoint: .bottom)
    }

    /// Which `palette` pair `seed` gets: the same one on every launch and every device, and the
    /// same one the web client's `avatarPalette` picks for that seed.
    static func paletteIndex(for seed: String) -> Int {
        // Human: Never use `hashValue` (or `Hasher`) here. Swift seeds it randomly for each process,
        // so the same contact came up pink, purple and grey on successive launches. This is the web
        // client's `avatarPalette` hash, FNV-1a plus a finalizer, over UTF-16 code units — the
        // units `charCodeAt` reads, so "Müller" is the same colour on both. Keep the finalizer:
        // with eight colours only the low three bits count, and FNV-1a alone makes those depend on
        // nothing but the low three bits of each unit, so "jane" and "jine" would always match.
        // Agent: RETURNS 0..<palette.count; pure UInt32 wrapping arithmetic, no I/O; must stay
        // bit-identical to avatarPalette in web/src/components/Avatar.tsx (AvatarPaletteTests).
        var hash: UInt32 = 0x811C_9DC5
        for unit in seed.utf16 {
            hash ^= UInt32(unit)
            hash &*= 0x0100_0193
        }
        hash ^= hash >> 13
        hash &*= 0x5BD1_E995
        hash ^= hash >> 15
        return Int(hash % UInt32(palette.count))
    }

    static func initials(for name: String) -> String {
        let parts = name
            .split(whereSeparator: { $0.isWhitespace || $0 == "@" })
            .map(String.init)
            .filter { !$0.isEmpty }
        if parts.isEmpty { return "?" }
        if parts.count == 1 {
            return String(parts[0].prefix(2)).uppercased()
        }
        let first = parts[0].prefix(1)
        let second = parts[1].prefix(1)
        return "\(first)\(second)".uppercased()
    }
}

#Preview {
    HStack(spacing: 12) {
        AvatarView(initials: "JC")
        AvatarView(initials: "DT", gradient: AvatarView.gradient(for: "Design Team"))
        AvatarView(initials: "NV", size: 64, fontSize: 23)
    }
    .padding()
}
