RESUME of a paused run. Read /Users/nvorberg/Documents/development/shroud/.claude/android-ui/BRIEF.md fully, including both "Update" sections at the end, and /Users/nvorberg/Documents/development/shroud/.claude/android-ui/GAPS.md. Follow them. Repo: /Users/nvorberg/Documents/development/shroud (you are in an isolated worktree of it). Your item prompt is /Users/nvorberg/Documents/development/shroud/.claude/android-ui/prompts/c3.md, unchanged: device allowance shroud_api37 on port 5560, stack shroud-c3. Read it and follow it.

Your branch claude/c3-shell (tip f7df295) holds your earlier work. You were paused in the middle of the device smoke test (during sign-up on emulator-5560). The emulator and the shroud-c3 stack are down. First:
1. `git checkout claude/c3-shell`
2. `git merge claude/android-ui`. The base now also has C13, C12, C9, C14 and Grok's 7e1181b; resolve conflicts in your own paths.

Then make sure RootScreen uses `callCoversApp(callActive)` from ui/calls/InCallOverlay.kt in its hiddenFromAccessibility conditions (C14's note), and finish the item.

Auto-lock:
- grok/g9-prep is still NOT in the base. The lead merges it right after your item lands. Until then, delayed and Never auto-lock are proven in JVM tests, and the device check is owed.

Device smoke test:
- Cold-boot with `-no-snapshot`, PIN 1234, then android/e2e/unlock.sh until `dumpsys user` says RUNNING_UNLOCKED.
- Always run with ANDROID_SERIAL=emulator-5560 or `adb -s emulator-5560`. Never touch emulator-5554.

When you are done, stop the stack and kill the emulator. Deliver the BRIEF's final report.
