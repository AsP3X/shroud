# Shroud for Android

Kotlin + Jetpack Compose. Plan and open decisions: [docs/android-plan.md](../docs/android-plan.md).
Design: `design/Android-App.pen` — keep it in sync with every UI change (see `CLAUDE.md`).

**One build, no Google.** There are no product flavors and no Firebase, Google Play services,
ML Kit, Tink, Play Core or analytics code anywhere. Push is UnifiedPush through a distributor the
user installs (e.g. ntfy) plus an opt-in "Background connection"; both arrive in later waves.

## Build and run

Needs JDK 21 and the Android SDK with platform 37. `gradle/gradle-daemon-jvm.properties` pins
the Gradle daemon to JDK 21 (the compiler toolchain is 21 too): Gradle picks an installed JDK 21
and never downloads one, so install it first. Android Studio honours the same criteria.

```bash
cd android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Or open `android/` in Android Studio and run the `app` configuration.

The debug build defaults to a self-hosted server at `http://10.0.2.2:8080/api/v1` — the local
stack as the emulator sees the host (see *End-to-end* below). On a physical phone, open the gear
on Welcome and enter your computer's LAN address. Release builds default to the official server.
Sign-up needs a screen lock on the phone. On Android 17 the app asks for local-network (Nearby
devices) access before it reaches a LAN or emulator-host server.

## Checks (CI: `.github/workflows/android.yml`)

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:verifyNoMaterial :app:verifyNoGoogleServices :app:assembleRelease
```

- `verifyNoMaterial` fails on any Compose Material reference in the sources or among the shipped
  libraries: the app draws its own chrome from `ui/theme` tokens.
- `verifyNoGoogleServices` fails if a Firebase, Google Play services, ML Kit, Tink, Play Core,
  install-referrer or data-transport module reaches the debug or release runtime classpath, or if
  the merged release manifest mentions GMS, C2DM or Firebase. Both run as part of `check`.
- CI then lists the release APK's dex packages and manifest with `apkanalyzer` and fails on any
  Google services class or entry.

## Layout

| Path | What |
| --- | --- |
| `app/src/main/java/.../AppContainer.kt` | One per process, built after the first unlock: a registry of lazily built `di/` modules |
| `app/src/main/java/.../di` | One module per package (`NetModule`, `KeysModule`, `AuthModule`, …); only its package fills it |
| `app/src/main/java/.../core/lifecycle` | `AppPhaseMonitor`: Active / Inactive / Background from our activities (iOS scene phase) |
| `app/src/main/java/.../core/model` | `Ids` (UUIDs, lower-case wire form), `Bytes`, `AppClock`, small shared types |
| `app/src/main/java/.../core/net` | `ApiClient` (OkHttp + kotlinx.serialization), `ShroudApi`, server settings; UUID-typed DTOs in `dto/`, strict dates and serializers in `wire/` |
| `app/src/main/java/.../core/realtime` | `RealtimeEvent` and the foreground contracts (the client arrives in wave 1) |
| `app/src/main/java/.../core/crypto` | BIP39, phrase → identity keys, key bundle, `CryptoController`; `ByteOps` (strict Base64, hex), `Primitives` (X25519, HKDF, HMAC, AES-GCM), `LocalHistoryCrypto` (sealed records), `PeerLocks` |
| `app/src/main/java/.../core/keys` | `LocalNames` (keyed file names) and the key-record seams |
| `app/src/main/java/.../core/auth` | Session (`SessionController`, Keystore-sealed `SessionStore`), password strength |
| `app/src/main/java/.../core/storage` | `KeystoreSealer`, sealed files in no-backup storage, `StorageSeal` (blocks writes during a wipe) |
| `app/src/main/java/.../ui/theme` | Colour tokens (light/dark), Inter, motion, the design's icons |
| `app/src/main/java/.../ui/components` | The app's own chrome: buttons, glass controls, sheet, toggle, toast, onboarding parts |
| `app/src/main/java/.../ui/onboarding` | Welcome, Server settings, Sign Up, Log In |
| `app/src/test/java/.../testing` | JVM test kit: `MainDispatcherRule`, `FakeSharedPreferences`, `XorSealer`, `FakeAppClock`, `TempDirRule`, `SealedTestKey` |
| `e2e/` | Local stack and adb scripts (below) |

Manifest components that later waves fill in exist as stubs today: `CallActivity`, `CallService`,
`UnifiedPushReceiver`, `BackgroundConnectionService`, `BootCompletedReceiver`,
`DecryptedMediaProvider`. The launcher entry points are two aliases of `MainActivity`
(`.LauncherDetailed`, enabled, and `.LauncherSimple`), switched by the logo setting.

## End-to-end (local stack)

Needs Docker, cargo and the Android SDK; nothing leaves the machine.

```bash
e2e/stack-up.sh             # Postgres, Redis, ntfy (the push service) in Docker + the API from server/ on :8080
e2e/emulator-setup.sh       # per emulator: PIN 1234, ACCESS_LOCAL_NETWORK (Android 17), adb reverse for ntfy
e2e/unlock.sh               # after a cold boot: wake and enter the PIN if the lock screen shows
e2e/launch.sh               # force-stop + cold start, prints the launch time
e2e/ui.py dump | tap <text> | wait <text> | type <text> | key <code> | shot <file>
e2e/enter_phrase.sh "<12 words>"
e2e/reset-limits.sh         # flush the rate-limit windows (auth 10 a minute for every local client together)
e2e/stack-down.sh
```

`SERIAL` picks a device when several are attached; ports, container names and the state folder
(server log, media) are in `e2e/common.sh` and can be overridden from the environment. Use AOSP
emulator images without Google APIs: the app must not need them.

## Conventions

- iOS is the reference implementation. Port against its code and tests; wire formats must be
  byte-compatible (golden vectors, e.g. `IdentityKeyMaterialTest`). Cite the iOS `file:line` in
  KDoc or test names for ported behaviour.
- Controllers live in `AppContainer` modules, never in an activity; composables reach them
  through `LocalAppContainer`.
- Copy, spacing and colours come from the design file; motion from `ui/theme/Motion.kt`, never
  ad-hoc springs.
- Never log tokens, keys, names or plaintext.
- Fonts: Inter 4.1 (OFL), icons: Lucide and Phosphor paths. Licences in `app/src/main/assets/licenses/`.
