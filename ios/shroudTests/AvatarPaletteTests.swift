import Testing
@testable import shroud

/// A contact keeps one avatar colour. The palette index used to come from `String.hashValue`,
/// which Swift seeds randomly for each process, so the call screen showed "Jane Cooper" pink,
/// purple and grey on successive launches. The expected indexes were computed with the web
/// client's `avatarPalette` (`web/src/components/Avatar.tsx`), so they also pin that both
/// clients share one hash.
@MainActor
struct AvatarPaletteTests {
    /// The regression this suite exists for.
    @Test
    func nameKeepsItsColourOnEveryLaunch() {
        // Human: A per-process seed (the old `hashValue`) would pass all three only about once
        // in 512 runs, so a return of it fails here rather than on someone's call screen.
        // Agent: CALLS AvatarView.paletteIndex(for:); values from avatarPalette in Avatar.tsx.
        #expect(AvatarView.paletteIndex(for: "Jane Cooper") == 7)
        #expect(AvatarView.paletteIndex(for: "jane_cooper") == 3)
        #expect(AvatarView.paletteIndex(for: "Design Team") == 5)
    }

    /// The web seeds with user ids (lowercase UUIDs); the same seed must give the same pair on the
    /// iPhone. "jane" and "jine" differ only in the high bits of one byte: without the finalizer
    /// they would always share a colour.
    @Test
    func seedPicksTheSamePairAsTheWebClient() {
        #expect(AvatarView.paletteIndex(for: "") == 0)
        #expect(AvatarView.paletteIndex(for: "3f2c8a9e-5b1d-4c7a-9e2f-8d6b4a1c0e7f") == 7)
        #expect(AvatarView.paletteIndex(for: "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d") == 2)
        #expect(AvatarView.paletteIndex(for: "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed") == 0)
        #expect(AvatarView.paletteIndex(for: "jane") == 2)
        #expect(AvatarView.paletteIndex(for: "jine") == 4)
        // Non-ASCII must follow the web client's UTF-16 code units, not the UTF-8 bytes.
        #expect(AvatarView.paletteIndex(for: "ä") == 4)
        #expect(AvatarView.paletteIndex(for: "👋") == 3)
    }
}
