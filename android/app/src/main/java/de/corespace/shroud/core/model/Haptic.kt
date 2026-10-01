package de.corespace.shroud.core.model

/**
 * Haptic intents (plan §1.7.1, conflict C19). A pure enum so engines can ask for feedback without
 * a `View`; the mapping to `HapticFeedbackConstants` lives in `ui/theme/Haptics.kt` (W1-UI-THEME,
 * table in plan §1.7.12). iOS reaches for `Haptics.impact(_:)` / `Haptics.notification(_:)`
 * directly (`ShroudUI/Theme/Haptics.swift:7-21`).
 */
enum class Haptic {
    /** No feedback (a call site that may or may not buzz). */
    None,

    /** iOS impact `.light`. */
    Light,

    /** iOS impact `.medium`. */
    Medium,

    /** iOS impact `.soft`. */
    Soft,

    /** iOS impact `.rigid` (recording discarded). */
    Rigid,

    /** iOS impact `.heavy` (swipe armed). */
    Heavy,

    /** A long-press menu opening. */
    LongPress,

    /** iOS notification `.success`. */
    Success,

    /** iOS notification `.warning`. */
    Warning,

    /** iOS notification `.error`. */
    Error,

    /** Success when a voice recording locks. */
    LockEngaged,

    /** Pull-to-refresh crossing its threshold. */
    SegmentTick,
}
