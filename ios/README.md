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

## Run

1. Start the API: `docker compose up -d` then `cd server && cargo run -p shroud-server`
2. Open `shroud.xcodeproj` in Xcode
3. Run on an iOS Simulator — Debug uses `http://127.0.0.1:8080/api/v1`

## Onboarding flow (implemented)

`Welcome` → `Sign Up` or `Log In Flow` (single screen; hero, content slot, and actions morph in place) → main shell placeholder

Design reference: `Log In Flow — Credentials Step` and `Log In Flow — Phrase Step` in `design/iOS-App.pen` (two state variants of one morphing screen).
