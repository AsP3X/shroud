package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.auth.SessionStore
import de.corespace.shroud.core.storage.KeystoreSealer
import de.corespace.shroud.core.storage.SealedFile
import java.io.File

/**
 * Session, Log Out / removal wipe, device names, appearance (00-plan §1.7.6). Owner: W2-AUTH-WIPE
 * (`DeviceWipeController`, `DeviceDataWipe`, `ColorThemePreference`, `BrandLogoPreference`, …);
 * W0-A moved the existing [SessionController] wiring here unchanged.
 */
class AuthModule(container: AppContainer) : AppModule(container) {
    /** Session token and device anchor: AFU `shroud.session.v1` in no-backup storage (00-plan §1.5). */
    private val sessionSealer by lazy { KeystoreSealer("shroud.session.v1") }

    val sessionController: SessionController by lazy {
        val dir = container.appContext.noBackupFilesDir
        SessionController(
            container.net.api,
            SessionStore(
                sessionFile = SealedFile(File(dir, "session.sealed"), sessionSealer),
                anchorFile = SealedFile(File(dir, "device-anchor.sealed"), sessionSealer),
                json = container.json,
            ),
            appScope = container.appScope,
            // No keys outlive the session. Log Out and a removal also delete the stored identity and
            // vault (iOS DeviceWipeController.swift:209, :297); a plain sign-out keeps them
            // (RootView.swift:213). W2-AUTH-WIPE's wipe takes this over.
            onSignedOut = { wipe -> container.keys.cryptoController.lock(wipeStore = wipe) },
        )
    }
}
