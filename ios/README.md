# Shroud iOS

Native SwiftUI client for the E2E encrypted messenger.

## Structure

| Path | Role |
| --- | --- |
| `shroud/App/` | `@main` entry |
| `shroud/Features/` | Screens (MVVM) — 1:1 with `design/iOS-App.pen` |
| `shroud/ShroudUI/` | Reusable components + `Theme` tokens |
| `shroud/Services/` | API client, crypto, persistence (no keys in views) |
| `shroud/Resources/` | Asset catalog (design tokens) |
| `ShroudNotificationService/` | Notification service extension (sealed sender names) |
| `ShroudScreenShare/` | Broadcast upload extension: shares the whole screen into a call (docs/calls.md, "Screen sharing") |
| `ShroudShared/` | Code compiled into the app and its extensions |

Both extensions sign with the team's automatic signing and need the app group
`group.de.corespace.shroud` on their App IDs (`de.corespace.shroud.NotificationService`,
`de.corespace.shroud.ScreenShare`). Xcode registers a new one on the first device build; if it
reports a missing app group, add it to that App ID in the developer portal.

## Run

1. Start the API: `docker compose up -d` then `cd server && cargo run -p shroud-server`
2. Open `shroud.xcodeproj` in Xcode
3. Run on an iOS Simulator — Debug uses `http://127.0.0.1:8080/api/v1`

## Onboarding flow (implemented)

`Welcome` → `Sign Up` or `Log In Flow` (Zoom transition from the Welcome brand mark) → main shell placeholder.

`Log In Flow` is a single screen: hero, content slot, and actions morph between credentials and phrase steps.

Design reference: `Log In Flow — Credentials Step` and `Log In Flow — Phrase Step` in `design/iOS-App.pen` (two state variants of one morphing screen).
