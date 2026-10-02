package de.corespace.shroud.di

import android.content.Context
import android.provider.Settings
import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.appearance.BrandLogoPreference
import de.corespace.shroud.core.appearance.ColorThemePreference
import de.corespace.shroud.core.auth.AndroidPrefsAccess
import de.corespace.shroud.core.auth.AndroidSystemWipe
import de.corespace.shroud.core.auth.DeviceDataWipe
import de.corespace.shroud.core.auth.DeviceNameSync
import de.corespace.shroud.core.auth.DeviceWipeController
import de.corespace.shroud.core.auth.OnboardingService
import de.corespace.shroud.core.auth.RemovalWake
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.auth.SessionStore
import de.corespace.shroud.core.auth.ShroudOnboardingService
import de.corespace.shroud.core.auth.WipeKeepList
import de.corespace.shroud.core.auth.WipeLocations
import de.corespace.shroud.core.crypto.CryptoController
import de.corespace.shroud.core.crypto.DeviceNameSeal
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.devices.DevicesController
import de.corespace.shroud.core.devices.ShroudDevicesController
import de.corespace.shroud.core.keys.KeyMaterialWipe
import de.corespace.shroud.core.storage.KeystoreSealer
import de.corespace.shroud.core.storage.PrefsFiles
import de.corespace.shroud.core.storage.SealedFile
import java.io.File
import java.util.UUID

/**
 * Session, Log Out / removal wipe, device names, appearance (00-plan §1.7.6; settings-lock §5, §8,
 * §13, §14). Owner: W2-AUTH-WIPE. Nobody else constructs these classes (§2.0 rule 3).
 *
 * Wiring the shell (INT, then W3-SHELL) does with them, as iOS `RootView` does (`RootView.swift:129-194`):
 * - at launch, before the session is used: `deviceWipe.finishInterruptedWipeIfNeeded()` → true shows
 *   "Signed out · this phone was cleared";
 * - whenever `sessionController.pendingFullLocalWipe` turns true: `deviceWipe.startIfSessionEnded()`;
 * - Log Out: `deviceWipe.start(WipeReason.Logout)`; while its composition lives, `deviceWipe.router`;
 * - after every unlock and at launch when unlocked: [syncDeviceName];
 * - the root theme from [colorTheme], the launcher style from [brandLogo].
 */
class AuthModule(container: AppContainer) : AppModule(container) {
    private val app: Context get() = container.appContext

    /** Session token and device anchor: AFU `shroud.session.v1` in no-backup storage (00-plan §1.5). */
    private val sessionSealer by lazy { KeystoreSealer(SESSION_ALIAS) }

    /** `session.sealed` + `device-anchor.sealed` (settings-lock addendum SessionStore). */
    val sessionStore: SessionStore by lazy {
        val dir = app.noBackupFilesDir
        SessionStore(
            sessionFile = SealedFile(File(dir, SESSION_FILE), sessionSealer),
            anchorFile = SealedFile(File(dir, ANCHOR_FILE), sessionSealer),
            json = container.json,
            seal = container.storageSeal,
        )
    }

    val sessionController: SessionController by lazy {
        SessionController(
            api = container.net.api,
            store = sessionStore,
            appScope = container.appScope,
            // Built only when a session ends, so the session never builds the wipe up front.
            wipeMarker = { deviceDataWipe.markPending() },
            isWipePresented = { deviceWipe.isPresented.value },
            // The deprecated immediate Log Out still drops the keys (until the shell runs the wipe).
        )
    }

    /** Sign-up, log-in and the local-network check (K2). The [AppContainer] shims forward here. */
    val onboarding: OnboardingService by lazy {
        ShroudOnboardingService(
            sessions = sessionController,
            crypto = container.keys.cryptoController,
            deviceSecurity = container.keys.deviceSecurity,
            api = container.net.api,
            context = app,
            configuration = { container.serverConfiguration.configuration.value },
        )
    }

    private val devicesLazy = lazy {
        val crypto = container.keys.cryptoController
        ShroudDevicesController(
            listDevices = { token -> container.net.api.devices(token) },
            revokeDevice = { token, id -> container.net.api.revokeDevice(token, id) },
            putDeviceName = { token, id, sealedName -> container.net.api.putDeviceName(token, id, sealedName) },
            session = { sessionController.session.value },
            unlockedUserId = { crypto.unlockedUserId.value },
            openName = { sealed, id -> openDeviceName(crypto, sealed, id) },
            sealName = { label, id -> sealDeviceName(crypto, label, id) },
            deviceNoun = { DeviceNoun.current(app) },
        )
    }

    /** Settings › Devices (K3): `GET /devices`, revoke, and a sealed rename. */
    val devices: DevicesController by devicesLazy

    /** [devices] when something already built it. The wipe and the chat lock never build it just to clear it. */
    val devicesIfBuilt: DevicesController? get() = if (devicesLazy.isInitialized()) devices else null

    /** What survives the wipe; packages that keep more register here (settings-lock §14.3.2). */
    val wipeKeepList: WipeKeepList by lazy { WipeKeepList.forApp(locations) }

    private val locations: WipeLocations by lazy { WipeLocations.of(app) }

    val deviceDataWipe: DeviceDataWipe by lazy {
        DeviceDataWipe(
            locations = locations,
            keyMaterial = container.keys.keyMaterialWipe,
            keystore = KeyMaterialWipe.AndroidKeystoreAliases(),
            prefs = AndroidPrefsAccess(app, KNOWN_PREFS),
            keepList = wipeKeepList,
            system = AndroidSystemWipe(app) { container.net.http.connectionPool },
        )
    }

    /** The Log Out / forced sign-out / removal wipe behind the overlay (settings-lock §14.2). */
    val deviceWipe: DeviceWipeController by lazy {
        DeviceWipeController(
            dataWipe = deviceDataWipe,
            session = sessionController,
            hooks = container.wipeHooks,
            storageSeal = container.storageSeal,
            appScope = container.appScope,
            clock = container.clock,
            endServerSession = { token -> container.net.api.logout(token) },
            reduceMotion = { reduceMotion() },
            deviceNoun = { DeviceNoun.current(app) },
        )
    }

    /** The removal push's work (settings-lock §14.6); W3-PUSH reaches it through `DeviceRemovalWake.handle(container)`. */
    val removalWake: RemovalWake by lazy {
        RemovalWake(
            storedSession = { sessionStore.session },
            confirm = { session -> container.net.api.me(session.token) },
            session = sessionController,
            deviceWipe = deviceWipe,
            dataWipe = deviceDataWipe,
            hooks = container.wipeHooks,
            storageSeal = container.storageSeal,
            // The shell attaches its router while its composition lives (RootView's controllers exist).
            hasUi = { deviceWipe.router != null },
            clock = container.clock,
        )
    }

    /** Settings › Appearance theme, prefs `shroud.appearance` (settings-lock §8.2). */
    val colorTheme: ColorThemePreference by lazy {
        ColorThemePreference(app.getSharedPreferences(PrefsFiles.APPEARANCE, Context.MODE_PRIVATE), container.storageSeal)
    }

    /** Launcher icon style, from the `activity-alias` state (settings-lock §8.4). */
    val brandLogo: BrandLogoPreference by lazy { BrandLogoPreference(app) }

    /**
     * Seals this phone's name for the device list (`RootView.syncDeviceName`, `RootView.swift:306-313`):
     * needs the history key, so only while unlocked; best effort, never throws. The key is copied out
     * of the crypto controller for the call and zeroed after it. Keys of another account than the
     * session's (a sign-in in between) seal nothing: other devices could not open that name.
     */
    suspend fun syncDeviceName() {
        val session = sessionController.session.value ?: return
        val crypto = container.keys.cryptoController
        if (!session.userId.equals(crypto.unlockedUserId.value, ignoreCase = true)) return
        val historyKey = crypto.withMaterial { it.historyKey.copyOf() } ?: return
        try {
            DeviceNameSync.syncIfNeeded(container.net.api, session, historyKey, DeviceNameSync.currentLabel(app))
        } finally {
            historyKey.fill(0)
        }
    }

    /**
     * Opens a sealed device name with this session's history key. Null while the chats are locked,
     * or when the unlocked account is not the session's — another account's key must not be tried.
     */
    private fun openDeviceName(crypto: CryptoController, sealed: String?, id: UUID): DeviceNameSeal.Label? {
        val user = sessionController.session.value?.userId ?: return null
        if (!user.equals(crypto.unlockedUserId.value, ignoreCase = true)) return null
        val key = crypto.withMaterial { it.historyKey.copyOf() } ?: return null
        try {
            return DeviceNameSeal.open(sealed, id, key)
        } finally {
            key.fill(0)
        }
    }

    /** Seals [label] for [id]. Null while locked or for another account. [DeviceNameSeal.SealError.EmptyName] propagates. */
    private fun sealDeviceName(crypto: CryptoController, label: DeviceNameSeal.Label, id: UUID): String? {
        val user = sessionController.session.value?.userId ?: return null
        if (!user.equals(crypto.unlockedUserId.value, ignoreCase = true)) return null
        val key = crypto.withMaterial { it.historyKey.copyOf() } ?: return null
        try {
            return DeviceNameSeal.seal(label, id, key)
        } finally {
            key.fill(0)
        }
    }

    /** Android "Remove animations" (the animator duration scale is 0), as `ui/theme` reads it. */
    private fun reduceMotion(): Boolean =
        runCatching { Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

    companion object {
        const val SESSION_ALIAS = "shroud.session.v1"
        const val SESSION_FILE = "session.sealed"
        const val ANCHOR_FILE = "device-anchor.sealed"

        /** Every prefs file the app writes (00-plan §1.5), also before `apply()` reached the disk. */
        internal val KNOWN_PREFS = listOf(
            PrefsFiles.SERVER, PrefsFiles.DEVICE, PrefsFiles.WIPE, PrefsFiles.PREFERENCES, PrefsFiles.APPEARANCE,
            PrefsFiles.NOTIFICATIONS, PrefsFiles.MESSAGING, PrefsFiles.VOICE, PrefsFiles.PUSH, PrefsFiles.UI,
        )
    }
}
