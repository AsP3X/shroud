# Android UI pass (C1–C18): progress ledger

- **Base:** `claude/android-ui`, created from `grok/phase-a` 9b55a10 on 2026-10-02. Agents read `.claude/android-ui/BRIEF.md`.
- **Pacing:** check `get_usage` before each launch. Keep the 5-hour window below about 85% when projected. If it gets close, sleep until the reset; never let the limit kill running agents. Weekly all-models was 59% at the start (resets 2026-10-04 23:59 UTC). Extra usage is enabled and paid: stop and ask the owner before going over the weekly limit.
- **Paused 2026-10-02 ~12:45 UTC** at 80% of the 5-hour window (reset 16:20 UTC). Relaunch C12, C13, C14, C3 and C9 with the prompts in `.claude/android-ui/prompts/`.
- **Before relaunching at 18:00 UTC** (the owner moved the resume from 16:22 to 18:00 UTC): read Grok's new handover (the owner said Grok writes one for this resume; look for new `docs/android-handover*` files and new `grok/*` branches). Fold any contract changes or gap fixes into the agent prompts, the BRIEF and GAPS.md. Then relaunch.
- **2026-10-02:** the GitHub repo is now PUBLIC. The owner approved pushing `dev` only (8b6c976 pushed). `grok/*` and `claude/*` stay local until the owner says so, and `.gitignore` stays unchanged.
- **Second pause, 2026-10-02 17:58 UTC,** at 81% of the 5-hour window (reset 21:20 UTC; weekly 82%, no extra usage). Next window: resume C3, C5, C4 and C11 (prompts in `prompts/`; resume prompt = check out the branch, merge claude/android-ui, continue), then C10, C7+C15, C8 and C6. Weekly will be about 95% after the next window, a hard stop until 2026-10-05 00:00 UTC.
- **18:30 UTC:** merged grok/phase-a 7e1181b into claude/android-ui (9c3604e); gaps 8–15 are done in core (see GAPS.md). g9-prep (d77dd2c) goes in only after C3. UI follow-ups: C13 drops the MediaViewDeviceTest skip and reruns it on api30 and moves to bindState/zoomRange/hasFlashUnit/shareTarget; C14 switches to the route list; C10 handles the emoji2 deprecation.
- **~22:55 UTC:** grok/g9-prep d77dd2c merged into claude/android-ui (fbcc342) after C3. Production attach is in and the interim ON_STOP lock is gone. Gate green: 3549 tests. C6 merged as 19a2a3f, plus b6f5cfb (SettingsScreen keys on userId, not the token).
- **2026-10-03 ~23:30 UTC:** the owner handed the rest to Grok so they can test on a real phone today (`.claude/android-ui/HANDOVER-to-grok-device-test.md`): Part A on branch `integration/device-test` (manifest #21, gate, quick device runs, phone setup via adb reverse to the local stack), Part B (gaps 16–22, G10/G11), Part C (C14b, C5 tidy-up, kit fixes, C17). C16 design stays with Claude after 2026-10-05. When resuming, check `integration/device-test` and Grok's report first.
- **Resume recipe:**
  1. For each `claude/c*` branch, if a worktree is left under `.claude/worktrees`, WIP-commit it, then `git worktree remove --force` (the branch stays).
  2. Relaunch the item with the same prompt. The agent checks out the existing branch and continues.

| Item | Branch | Status | Head | Notes |
| --- | --- | --- | --- | --- |
| BASE claude/android-ui (6f0530f: all of C1–C15 + grok 7e1181b + G9; FINAL gate green: 3618 tests, verifyNoGoogleClasses, assembleRelease) | — | merged grok/phase-a f3c8065 + 2 UI fixes (AlreadyRemoved toast, durationMs); g9-prep NOT merged (GAPS #8) | b9ea817 | gate green: 2884 tests |
| C1+C2 integrate W3 UI + retarget to K2–K5 | claude/c1-integrate | DONE, ff'd into claude/android-ui | dad4de2 | 2868 tests green; gaps → GAPS.md | merges the 9 android/w3-* branches |
| C12 photo compose + editors | claude/c12-media-edit | DONE, merged into claude/android-ui | 44b6ac1 | 80 tests; gate 3046 green; device checks owed | from base 9b55a10 |
| C13 camera, video compose, viewer, sharing | claude/c13-media-view | DONE, merged | bfe3134 | 65 tests; device checks on api30 passed; video capture blocked by core QualitySelector (GAPS #13) | emulator shroud_api30:5562 |
| C14 call screen, Calls tab, CallActivity | claude/c14-calls-ui | DONE, merged into claude/android-ui (479b84a) | 37486ca | 82 calls tests; device checks owed after G9 | no attach (G9); device call owed after G9 |
| C3 shell | claude/c3-shell | RUNNING (resumed 21:21 UTC) | f7df295 | emulator shroud_api37:5560, stack shroud-c3 | |
| C4 lock/wipe/onboarding | claude/c4-lock-onboard | DONE, merged | 43c03bc | 138 tests; gate 3288 green; LockOnboardJourneyTest + DeviceWipeOverlayTest owed to C17; hero zoom wiring is C3's (RootScreen) | prompt prompts/c4.md |
| C5 chats | claude/c5-chats | DONE, merged | bb004ac | gate 3250 green; 25 PNGs in build/outputs/c5-screens; toast workaround until C3 | prompt prompts/c5.md |
| C6 settings A | claude/c6-settings-a | DONE, merged (19a2a3f) | 1e36f36 | gate 3547 green; fixed the bar icons after rotation; theme cross-fade; logo switch blocked by manifest CR-1 (GAPS #21) | prompt prompts/c6.md |
| C7+C15 settings B + push Delivery UI | claude/c7-settings-b | DONE, merged | cb3b5ad | gate 3431 green; 44 PNGs; regenerated the icon kit (ShroudIcons.kt, gen script, assets/licenses/icons.txt) | prompt prompts/c7.md |
| C8 contacts | claude/c8-contacts | DONE, merged | b056fa8 | 117 tests; gate 3430 green; ContactsJourneyTest owed to C17 | prompt prompts/c8.md |
| C9 conversation screen | claude/c9-thread-list | DONE, merged (86d9d70) | f1b72e3 | 100 tests; MessageHoldDeviceTest owed to C17 | biggest gap: screen was a stub | |
| C10 bubbles | claude/c10-bubbles | DONE, merged (6f0530f) | 5637a1f | gate 3575 green; emoji2 deprecation fixed; one BubbleServices per container; caches cleared on lock/purge tested | prompt prompts/c10.md; includes emoji2 deprecation |
| C11 composer | claude/c11-composer | DONE, merged | 79ebb2e | 134 tests; gate 3259 green; ComposerDeviceTest owed to C17 | prompt prompts/c11.md |
| C13b camera/viewer on gaps 13–15 + MediaViewDeviceTest rerun on api30 | claude/c13b-media-followup | DONE, merged | ce8eb39 | gate 3627 green; MediaViewDeviceTest OK (5 tests) on api30 including the recorded clip | prompt prompts/c13b.md |
| C14b call screen route list (GAPS #10) | — | todo | | after the weekly reset |
| C16 design frames | (main checkout, Pencil) | blocked on OPEN-4 answer | | one agent, after code |
| C17 UI journeys + screenshot parity | claude/c17-journeys | todo (after merges) | | both emulators |
| C18 a11y + design reconciliation | claude/c18-a11y | todo (last) | | C4 note: components/Buttons.kt:74 .alpha(0.45f) clips the disabled PrimaryButton's dropShadow; use graphicsLayer with CompositingStrategy.ModulateAlpha. C8 note: ShroudSheet does not reset LocalGlassBackdrop (glass in sheets shows the screen behind); ToastHost still ignores LocalTabBarClearance until C3. C7 note: ToggleRow does not speak subtitles to TalkBack; align the Keystore notice wording between C4's lock screen and C7's Privacy |
