# Shroud for Android

Kotlin + Jetpack Compose. Plan and open decisions: [docs/android-plan.md](../docs/android-plan.md).
Design: `design/Android-App.pen` — keep it in sync with every UI change (see `CLAUDE.md`).

## Build and run

Needs JDK 21 (Android Studio's bundled JBR works) and the Android SDK with platform 37.

```bash
cd android
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest
```

Or open `android/` in Android Studio and run the `app` configuration.

The debug build defaults to a self-hosted server at `http://10.0.2.2:8080/api/v1` — the local
Compose stack as the emulator sees the host (see the root README). On a physical phone, open the
gear on Welcome and enter your computer's LAN address. Release builds default to the official
server. Sign-up needs a screen lock on the phone; on an emulator: `adb shell locksettings set-pin 1234`.
On Android 17 the app asks for local-network (Nearby devices) access before it reaches a LAN or
emulator-host server. The local server's auth rate limit (10 a minute from one address) is easy
to hit while testing.

## Layout

| Path | What |
| --- | --- |
| `app/src/main/java/.../core/net` | `ApiClient` (OkHttp + kotlinx.serialization), wire models, server settings |
| `app/src/main/java/.../core/crypto` | BIP39, phrase → identity keys, key bundle, `CryptoController` |
| `app/src/main/java/.../core/auth` | Session (`SessionController`, Keystore-sealed `SessionStore`), password strength |
| `app/src/main/java/.../core/storage` | `KeystoreSealer`, sealed files in no-backup storage |
| `app/src/main/java/.../ui/theme` | Colour tokens (light/dark), Inter, motion, the design's icons |
| `app/src/main/java/.../ui/components` | The app's own chrome: buttons, glass controls, sheet, toggle, toast, onboarding parts |
| `app/src/main/java/.../ui/onboarding` | Welcome, Server settings, Sign Up, Log In |

No Material 3 components: the app draws the iOS design itself, as the plan requires.
Dependencies are wired by hand in `AppContainer`.

## Conventions

- iOS is the reference implementation. Port against its code and tests; wire formats must be
  byte-compatible (golden vectors in `IdentityKeyMaterialTest`).
- Copy, spacing and colours come from the design file; motion from `ui/theme/Motion.kt`, never
  ad-hoc springs.
- Fonts: Inter 4.1 (OFL), icons: Lucide and Phosphor paths. Licences in `app/src/main/assets/licenses/`.
