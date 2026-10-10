# Delete Account in the apps — implementation plan (Grok = code, Claude = design)

**Status:** proposed 2026-10-09. Design C1–C4 drawn 2026-10-09; the frames are exported to
`docs/account-deletion/` (`ios-`, `ipad-`, `web-`, `android-` + `privacy-and-security`,
`delete-account`, `delete-account-{filled,deleting,wrong-password,too-many-tries,couldnt-reach}`,
`wipe-account-deleted`; Android also `privacy-and-security-dark`). G1–G4 are on `dev` (0df8546d).
C5 done 2026-10-10: see §5.1 for what matched and the four fixes left for Grok.
**Scope:** let a person delete their own account from iOS, the web client and Android. The server
already does the deletion (`DELETE /auth/account`, `routes/auth.rs`, documented in
`docs/server-plan.md` § `DELETE /auth/account`). No app offers it yet; Android has only the request
type (`core/net/dto/AuthDto.kt:131`, "no UI in v1, P18" — this plan replaces P18).
**Split:** Grok writes every line of code: the one server change, iOS, web, Android, and their tests
and docs. Claude does the design: every frame in `design/iOS-App.pen`, `design/iPad-App.pen`,
`design/webclient.pen` and `design/Android-App.pen`, and checks the built screens against them at
the end. Both work to the frozen contract and copy in §3; neither changes them without an entry in
§3.4 that the other reads before its next task.

---

## 1. What the owner decided (2026-10-09)

| # | Decision |
| - | -------- |
| D1 | All three apps get it in this pass: iOS (iPhone and iPad), web, Android. |
| D2 | A red **Delete Account** row in its own group at the bottom of **Privacy and Security**. Nowhere else. |
| D3 | Deletion is immediate, through the existing endpoint. No grace period, no auto-delete of inactive accounts. |
| D4 | One warning screen, the same text for everyone, then the account password. No Face ID / PIN step, no typed word. |
| D5 | The device that deleted shows the existing wipe screen with deletion wording, then the welcome screen. |
| D6 | The account's other devices say the account was deleted, not "this device was removed". |
| D7 | Password only. A forgotten password can't be used to delete; the 12-word phrase stays a key, not a login. |
| D8 | No account-specific counts on the warning. |

## 2. Rules for both agents

| # | Rule |
| - | ---- |
| R1 | `docs/agent-rules/*.md` apply: worktree from an up-to-date `dev`, no deprecated APIs, no AI attribution, every visible change also in the `.pen` files. |
| R2 | Grok never opens or edits a `.pen` file. Claude never edits code, tests or docs outside this plan. |
| R3 | The copy in §3.3 is the only copy. Code and frames use it word for word; a change goes through §3.4 first. |
| R4 | Older app builds must keep wiping a deleted account's devices. That is why §3.1 keeps the code `DEVICE_REMOVED` and adds a `reason`, rather than introducing a new code (old builds would treat an unknown 401 as a plain sign-out streak and keep the data). |
| R5 | The repository is public: no secret, real username or real password in code, tests, fixtures or frames. |

---

## 3. Frozen contract

### 3.1 Server: say *why* a device is gone

Every place the server tells a client `DEVICE_REMOVED` gains an optional `reason`. It is
`"account_deleted"` when the account has `deleted_at` set, and absent when only the device was
removed. Nothing else changes; `code` stays `DEVICE_REMOVED` everywhere.

| Where | Today | After |
| ----- | ----- | ----- |
| HTTP `401` body (`error.rs` `device_removed`, `auth/session.rs` `rejection_for`, `routes/auth.rs` `delete_account`) | `{"error":{"code":"DEVICE_REMOVED","message":"This device was removed from your account."}}` | deleted account: `{"error":{"code":"DEVICE_REMOVED","message":"This account was deleted.","reason":"account_deleted"}}`; removed device: unchanged |
| WebSocket `auth.error` on connect and on a live revoke (`routes/ws.rs` ~109 and ~291) | `{"type":"auth.error","error":{"code":"DEVICE_REMOVED",…}}` | same `error` object as the HTTP body above |
| APNs wake (`push/payload.rs` `apns_device_removed`) | `{"aps":{"content-available":1},"type":"device_removed"}` | deleted account adds `"reason":"account_deleted"` |
| Web Push / UnifiedPush wake (`web_device_removed`) | `{"v":1,"kind":"device_removed"}` | deleted account adds `"reason":"account_deleted"` |
| `POST /auth/session-status` | `{"removed":true}` | deleted account: `{"removed":true,"reason":"account_deleted"}`; otherwise unchanged |

- `ErrorDetail` gains `reason: Option<String>`, `skip_serializing_if = "Option::is_none"`.
- `rejection_for` and `token_hash_removed` must tell the two cases apart (device revoked vs user
  deleted). An account deleted *and* with this device revoked earlier answers `account_deleted`.
- `delete_user_account` passes the reason to `wake_removed_devices`, so both the user's own delete
  and the operator console's delete wake the other devices with `account_deleted`.
- `DELETE /auth/account` itself still answers `204`. When it answers `DEVICE_REMOVED` (another
  device deleted the account a moment ago), that body carries `account_deleted` too.

### 3.2 Calling `DELETE /auth/account` from the apps

Body `{"password":"…"}`. Every app maps the answers the same way:

| Answer | App does |
| ------ | -------- |
| `204` | Wipe this device with reason **account deleted** (§3.3 C10), then the welcome screen. |
| `401 INVALID_CREDENTIALS` | Stay on the screen, show C7 under the field, keep the password text selected. **This 401 must not count toward the session's 401 streak** (iOS `SessionController`, web `AppShell`/`client.ts`, Android `AuthOutcomeListener`): a wrong password is not a dead session. |
| `401 DEVICE_REMOVED` (any reason) | Wipe with reason **account deleted**. |
| `429` | C8. The budget is `AUTH_SENSITIVE_USER`, 5 per hour per account. |
| network error, timeout, `5xx` | C9. Nothing was deleted; the button works again. |

While the request runs, the button shows C6 and is disabled, the field and Cancel are disabled, and
the screen can't be dismissed (no swipe-down, no Escape, no back gesture). The password is never
stored, logged or kept in memory after the request.

### 3.3 Copy (word for word, all platforms)

`{device}` is the platform's existing word: "iPhone" / "iPad" (iOS), "browser" (web),
"phone" / "tablet" (Android) — whatever the wipe screen already uses there.

| Id | Where | Text |
| -- | ----- | ---- |
| C1 | Privacy and Security, last group, red row | Delete Account |
| C2 | Footer under that group | Deletes your account and erases it from every device. |
| C3 | Warning screen title | Delete your account? |
| C4 | Warning screen list, in this order | • Your messages are replaced with “Message deleted” for everyone. <br>• Contacts who let you clear chats for them lose those chats. Everyone else keeps their own messages. <br>• Your contacts, Saved Messages, photos, files and call history are deleted. <br>• Your username and share code are released, so someone else can take them. <br>• Every device signed in to this account is signed out and erased. |
| C5 | Below the list, above the field | This can't be undone. Enter your password to confirm. |
| C5a | Field label / placeholder | Password / Your account password |
| C6 | Destructive button / while running | Delete Account / Deleting… — plus a plain **Cancel** |
| C7 | Error, wrong password | That password isn't right. |
| C8 | Error, rate limited | Too many tries. Try again later. |
| C9 | Error, offline or server | Couldn't reach the server. Your account wasn't deleted. |
| C10 | Wipe screen lead for reason **account deleted** (this device and the others) | This account was deleted.  (followed, as today, by "Removing everything Shroud stored on this {device}.") |

The button is disabled until the field is non-empty. The wipe screen's other lines (steps, done,
failed) stay as they are.

### 3.4 Contract changes

| # | Date | Change | Asked by |
| - | ---- | ------ | -------- |
| 1 | 2026-10-09 | Wipe screen for reason **account deleted**: the footnote under the steps (iOS "Your account and chats on other devices stay as they are.", and its web and Android equivalents) is hidden, because it would be false. No new copy. | Claude (C1) |
| 2 | 2026-10-09 | Layout, all platforms: C2 is the row's subtitle inside the Delete Account card (as "Lock chats now" carries its subtitle), not a footer below it. The card is the last one on the screen, after "Encrypted on this device". The row has a red trash icon tile, the title in red and a chevron. Errors C7–C9 sit as red 13 pt text directly under the password card. | Claude (C1) |
| 3 | 2026-10-10 | iPad: accepted difference. The frames in `design/iPad-App.pen` show a sidebar and a 600 pt detail column; the app has no split view anywhere, and Privacy and Security, the screen that opens Delete Account, is one full-width column too. Delete Account follows that screen (16 pt margins, full-width cards and button, buttons directly under the field). The frames stay as the target for a later iPad layout pass; that pass is not part of Delete Account. | Claude (C5) |
| 4 | 2026-10-10 | iPhone and iPad, wrong password: accepted difference. §3.2 says the password stays selected; the frames show it highlighted. The secure field shows no selection in the app (`ios-delete-account-wrong-password.png`), and iOS replaces a secure field's contents on the first keystroke after it is refocused, so retyping works without it. Web and Android do select it. | Claude (C5) |

---

## 4. Grok backlog (code)

Work in a worktree from an up-to-date `dev`. One commit per task, plain message, no AI attribution.

### G1 — Server: the `reason` (§3.1)
- `error.rs`: `ErrorDetail.reason`; `AppError::account_deleted()` (code `DEVICE_REMOVED`, message
  and reason per §3.1). Keep `device_removed()` as is.
- `auth/session.rs`: `rejection_for` / `token_hash_removed` return which case; `routes/auth.rs`
  `session_status` and `delete_account`, `routes/ws.rs` both `auth.error` sites use it.
- `push/payload.rs` + `push/mod.rs`: wake payloads carry the reason; `delete_user_account` passes it.
- Tests (`tests/api_auth.rs`, `api_ws.rs`, `api_notifications.rs`, push payload unit tests): after
  `DELETE /auth/account`, a second device's token gets `reason: "account_deleted"` over HTTP, ws and
  session-status, and its recorded wake push carries it; after `DELETE /devices/:id` none of them
  has a `reason`. The operator delete (`api_operator.rs`) wakes with the reason too.
- Docs: `docs/server-plan.md` (the `DELETE /auth/account` section and the error table).
- Run the full server suite against Postgres and Redis and check none were skipped
  (`--nocapture`, grep for "skipping").

### G2 — iOS (`ios/`)
- `APIError` / `APIClient`: read `error.reason`; `isAccountDeletion`.
- `DeviceWipeController.Reason`: add `.accountDeleted`; `lead(for:)` returns C10. A wipe already
  running as `.removed` or `.sessionEnded` upgrades to `.accountDeleted` when the server's answer
  says so (same pattern as the existing `.sessionEnded → .removed` upgrade, ~line 299).
- `RealtimeClient` (`auth.error`), `DeviceRemovalWake` + `AppDelegate` (push `reason`), the
  session-status path: map `account_deleted` to `.accountDeleted`.
- `PrivacySecurityView.swift`: last section with C1/C2, pushing a new `DeleteAccountView` (C3–C9,
  §3.2 states). `SecureField` with `.textContentType(.password)`. Same on iPad.
- `SessionController`: exclude this call's `INVALID_CREDENTIALS` from the 401 streak.
- Tests: reason decoding, the reason upgrade, the answer mapping in §3.2, the streak exclusion.

### G3 — Web (`web/`)
- `api/client.ts`: `ApiError.reason`, `isAccountDeleted`. `realtime.ts`: pass the reason through
  `onFatalAuth`. `deviceRemoval.ts`: push `reason` and session-status `reason`.
- `DeviceWipeDialog.tsx`: an `accountDeleted` lead (C10); `AppShell` `endSession("accountDeleted")`.
- `components/settings/PrivacyView.tsx`: last `SettingsGroup` with C1/C2 opening a new settings
  sub-view (add it to `routes.ts`) with C3–C9 and the §3.2 states. `autocomplete="current-password"`.
- Exclude this call's `INVALID_CREDENTIALS` from the sign-out path.
- Selftests next to the existing ones (`realtime.selftest.ts`, `deviceRemoval.selftest.ts`, a new
  one for the view's answer mapping). `tsc -b` and the production build pass.

### G4 — Android (`android/`)
- `core/net/ApiError.kt`: `reason`, `isAccountDeleted`; `ShroudApi.deleteAccount(password)` using
  the existing `DeleteAccountRequest`. Remove the "no UI in v1, P18" note.
- `core/auth/WipeReason.kt`: `AccountDeleted` with C10; the same upgrade rule as iOS.
- Realtime `auth.error`, the UnifiedPush wake and `AuthOutcomeListener` carry the reason.
- `ui/settings/privacy/PrivacySecurityScreen.kt` + `PrivacySecurityModel.kt`: last group C1/C2,
  navigating to a new `DeleteAccountScreen` (C3–C9, §3.2 states); add the route to
  `SettingsDestination.kt`. Keep `ui/` forwarding only: the model calls core, `ui/` never touches
  `ShroudApi` directly.
- `AuthOutcomeListener` / `SessionController`: exclude this call's `INVALID_CREDENTIALS`.
- Unit and Compose tests next to `SettingsScreensUiTest.kt` / `SettingsCopyTest.kt` (copy matches
  §3.3). No deprecated APIs (rule).

### G5 — End to end
- Catch the worktree up with `dev` again, then on a local stack: sign in on iPhone simulator, web and
  an Android emulator with one account; delete it from one of them. The deleting device shows C10
  and lands on welcome; the other two show C10 (open socket, push wake, or the locked web client's
  session-status probe). A second account that had a chat with it sees "Deleted account" and
  "Message deleted". Wrong password shows C7 and does **not** sign the device out after three tries.
- Report each task by id with the test or run that proves it, and screenshots of every new state
  for Claude's check (C5).

## 5. Claude backlog (design)

Open each file in Pen first; edit only through the Pencil tools; reuse each file's components and
variables. The owner saves with ⌘S; before a `.pen` commit, audit that its diff holds only this work.

| # | File | Frames |
| - | ---- | ------ |
| C1 | `design/iOS-App.pen` | Privacy and Security with the new last group (C1, C2). New: "Delete Account" (empty, button disabled), "· Filled", "· Deleting", "· Wrong password", "· Too many tries", "· Couldn't reach". Wipe screen "· Account deleted" (running) next to the existing removed-device frame. |
| C2 | `design/iPad-App.pen` | The same, in the iPad layout, if the file has Privacy and Security; otherwise note that it has none. |
| C3 | `design/webclient.pen` | The same set for the web settings pane and `DeviceWipeDialog`. |
| C4 | `design/Android-App.pen` | The same set for the Android screens and wipe overlay. |
| C5 | — | After G2–G4: compare Grok's screenshots and the running apps against the frames (copy, spacing, colours, states); file differences as §3.4 entries or as fixes for Grok. |

Claude exports every new or changed frame as a PNG to `docs/account-deletion/<platform>-<frame>.png`
and commits them with the `.pen` change, so Grok can build from them without opening Pen.

C1–C4 run in parallel with G1. Grok's UI tasks (G2–G4) start from the frames once C1–C4 are done,
and from §3.3 alone if they aren't yet.

### 5.1 C5 result (2026-10-10)

Compared Grok's shots in `/tmp/g5-shots/` with the frames in `docs/account-deletion/`, pair by pair,
and the copy constants in `DeleteAccount.swift`, `deleteAccount.ts` and `DeleteAccountScreen.kt`
with §3.3: every string is word for word (C1–C10), on all three clients.

| Platform | Matches the frame | Differs |
| -------- | ----------------- | ------- |
| iPhone | privacy-and-security, delete-account, filled, too-many-tries, couldnt-reach, wipe-account-deleted | deleting (F1); wrong-password only in the missing selection (§3.4 #4) |
| iPad | copy, colours, states and order of every pair; wipe-account-deleted | layout of every pair (§3.4 #3); deleting (F1); wrong-password selection (§3.4 #4) |
| Web | privacy-and-security, delete-account, filled, wrong-password, too-many-tries, couldnt-reach, wipe-account-deleted (shots are the light theme of the same tokens) | deleting (F2) |
| Android | privacy-and-security, privacy-and-security-dark, deleting, wrong-password, too-many-tries, couldnt-reach, wipe-account-deleted | delete-account and filled in three details (F3) |

Not differences: C5's note wraps to two lines on iPhone and Android (system font metrics); the red
field border in the web deleting shot is the error border caught mid-fade on a retry; the amber
Keystore line on Android is the emulator's own.

Fixes for Grok:

| # | Where | Fix |
| - | ----- | --- |
| F1 | iOS `DeleteAccountView.swift`, iPhone and iPad | While deleting, Back, Cancel and the password card are disabled but look enabled (`ios-delete-account-deleting.png`, `ipad-…`). Fade them as the frame does: Back and Cancel to 0.35, the password card to 0.5. Cancel's explicit `foregroundStyle` and the toolbar stand-in's keep them at full strength. |
| F2 | Web `index.css` | While deleting, the password field stays at full strength: `.delete-account .afield-input:disabled` and `.afield-reveal:disabled` force `opacity: 1`. Fade the field to 0.5 as the frame does. Back and Cancel already fade. |
| F3 | Android `DeleteAccountScreen.kt` | Three details against `android-delete-account.png`: the "Password" label and Cancel are SemiBold, the frame has them Regular (as iOS does); the heading is 28 sp, the frame 26; the first consequence uses `ChatCircleDots`, which reads as typing, where the frame has a message with an x. |
| F4 | Web chat list, outside the frames | `web/peer-tombstone.png`: the "Deleted account" row shows the green online dot. A deleted account should show no presence. Check whether the server still reports it online or the web client keeps a stale presence entry. |

Proof gap, not a design difference: `ios-welcome-after-wipe.png`, `ipad-welcome-after-wipe.png` and
`ios-multi-welcome.png` show the wipe's done state ("This iPhone is clear", "Taking you to the
welcome screen…"), not the welcome screen. The done state is right and carries no other-devices
footnote; the welcome screen itself is shown only by the Android and web shots.

### 5.2 Re-check of the fixes (2026-10-10)

The fixes are four commits on `fix/delete-account-c5` (7be11546, 3b833c58, d62d1979, ef6b8059), not
yet on `dev`. Shots in `/tmp/c5-shots/`, compared with the same frames.

| Fix | Shot | Result |
| --- | ---- | ------ |
| F1 iPhone | `ios/delete-account-deleting.png` | Back, Cancel and the password card are faded as in the frame. **Open:** the password dots are missing from the faded field (the row shows only "Password"); the frame keeps them, dimmed. With the password revealed the text does show (`…-kept.png`, `…-1502.png`), and before the fade the dots showed while deleting (`/tmp/g5-shots/ios/ios-delete-account-deleting.png`). Either the 0.5 opacity on the card stops the secure field's dots being drawn, or it is an artefact of the test's paste. Check in the running app before merging; if real, fade the label, eye and background and leave the secure field out of the group opacity. |
| F1 iPad | none | `/tmp/c5-shots/ipad/` is empty. The view is shared with iPhone, so the same modifiers apply, but no screenshot shows it, and the dots question applies there too. |
| F2 web | `web/delete-account-deleting.png` | Matches: field, Back and Cancel faded, dots kept. |
| F3 Android | `android/delete-account.png` | Matches: label and Cancel Regular, heading 26 sp, message-with-x icon. |
| F4 web | `web/peer-deleted-no-dot.png` | "Deleted account" has no online dot. The delete now sends the peers `presence.update` with `online: false`; nothing about what is deleted changed. |
| iOS welcome | `ios/welcome-after-wipe.png` | Shows the welcome screen itself. The proof gap in §5.1 is closed for iPhone. |

## 6. Order

| Step | Needs | Unblocks |
| ---- | ----- | -------- |
| G1 | nothing | G2–G4 (they need `reason` to test against) |
| C1–C4 | nothing | G2–G4 layout |
| G2, G3, G4 | G1, C1–C4 | G5 |
| G5 | G2–G4 | C5 |
| C5 | G5 | done |

## 7. Do-not-touch

- `routes/auth.rs` `delete_user_account`'s deletion steps, migration 021 and anything about *what*
  is deleted: this pass only adds the reason and the UI.
- `DEVICE_REMOVED` as a code (R4). No new error code.
- The admin console (`admin/`): it already deletes accounts and needs nothing here.
- `.pen` files for Grok, code for Claude.

## 8. Handover prompt for Grok

> You are adding Delete Account to the Shroud apps. Read `docs/account-deletion-plan.md` fully, then
> `docs/agent-rules/*.md`, `docs/server-plan.md` § `DELETE /auth/account`, and the files §4 names.
> You write all code: G1 (server), G2 (iOS), G3 (web), G4 (Android), G5 (end to end), in that
> order; G2–G4 may run in parallel after G1. Use the copy in §3.3 word for word and match Claude's
> frames, exported as PNGs in `docs/account-deletion/` (never open or edit a `.pen` file). Keep `DEVICE_REMOVED` as the code; add
> only `reason`. A wrong password must never count toward a session's 401 streak. Work in a
> worktree based on an up-to-date `dev`; one commit per task, plain message, no AI attribution. If
> the contract or copy doesn't fit, add a line to §3.4 and stop for that item. Report done tasks by
> id with the test that proves each, and screenshots of every new state.

## 9. Resume prompt for Claude

> You do the design for Delete Account. Read `docs/account-deletion-plan.md` §1, §3, §5, §6. Open
> each `.pen` file in Pen and build C1–C4 with the file's own components, using §3.3 word for word.
> Export each new or changed frame to `docs/account-deletion/`. Ask the owner to save with ⌘S,
> audit each `.pen` diff against HEAD, then commit it with the PNGs. When Grok
> reports G5, do C5.
