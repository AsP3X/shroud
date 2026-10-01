package de.corespace.shroud.di

import android.content.Context
import android.media.projection.MediaProjectionManager
import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.calls.AndroidCallPermissions
import de.corespace.shroud.core.calls.CallController
import de.corespace.shroud.core.calls.CallPreferences
import de.corespace.shroud.core.calls.CallSecretStore
import de.corespace.shroud.core.calls.CallSecrets
import de.corespace.shroud.core.calls.ContactsCallPeers
import de.corespace.shroud.core.calls.RealtimeCallSocket
import de.corespace.shroud.core.calls.SealedCallSecretStore
import de.corespace.shroud.core.calls.ShroudCallsBackend
import de.corespace.shroud.core.contacts.Contacts
import de.corespace.shroud.core.contacts.PeerIdentities
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.lifecycle.AppPhase
import de.corespace.shroud.core.storage.KeystoreSealer
import de.corespace.shroud.core.storage.PrefsFiles
import de.corespace.shroud.core.storage.SealedFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Calls (00-plan §1.7.11, C29). Owner: W2-CALLS-CORE — the process's one [controller] (also the
 * `ActiveCallProbe`), [secrets] (each contact's call secret, AFU-sealed in
 * `noBackupFilesDir/call-secrets.sealed`), [preferences] (screen-share quality) and
 * [permissions] (the call UI registers its permission prompt there). Nobody else constructs these
 * classes (00-plan §2.0 rule 3).
 *
 * Integration (W2-INT wired these; W3-INT adds the media engine and the system):
 * - [peerIdentities] and [contacts] read `container.contacts` (W2-CONTACTS): pinned keys derive the
 *   secrets, the roster names callers;
 * - `AppContainer.onProcessStart` calls [onProcessStart] (it builds the controller, so socket rings
 *   are heard, keeps the secrets current and follows the app phase for the camera);
 * - `RealtimeModule.foregroundCoordinator` reads [controllerIfBuilt] as its `ActiveCallProbe`;
 * - `WipeHooksImpl.clearCalls()` → [wipe], `haltWriters` → `clearLocalState()`;
 * - `VoiceModule.bindCallMediaStarting(controller.callMediaStarting)`: a call's media stops voice
 *   playback and recording;
 * - W3-INT: `controller.attach(engine, system)` with W3-CALLS-MEDIA's engine and W3-CALLS-SYSTEM's system.
 */
class CallsModule(container: AppContainer) : AppModule(container) {
    private val app: Context get() = container.appContext

    /** W2-CONTACTS' `PeerIdentityController`: the pinned keys call secrets are derived from. */
    private val peerIdentities: () -> PeerIdentities? = { container.contacts.peerIdentities }

    /** W2-CONTACTS' `ContactsController`: who the contacts are (secrets for each, caller names). */
    private val contacts: () -> Contacts? = { container.contacts.controller }

    /** AFU: no user authentication, no `setUnlockedDeviceRequired` (plan §1.5, P3a; calls §11). */
    private val secretSealer: KeystoreSealer by lazy { KeystoreSealer(SECRETS_ALIAS) }

    /** `noBackupFilesDir/call-secrets.sealed`. */
    val secretStore: CallSecretStore by lazy {
        SealedCallSecretStore(
            file = SealedFile(File(app.noBackupFilesDir, SECRETS_FILE), secretSealer),
            seal = container.storageSeal,
            deleteKey = { secretSealer.deleteKey() },
        )
    }

    val secrets: CallSecrets by lazy {
        val crypto = container.keys.cryptoController
        CallSecrets(
            store = secretStore,
            isUnlocked = { crypto.isUnlocked },
            // X25519 with the identity key inside withMaterial: the private key never leaves it (C29).
            secretWith = { peerPublic ->
                crypto.withMaterial { material ->
                    CallSecrets.secretFromShared(material.agreement(peerPublic), material.identityPublicKey, peerPublic)
                }
            },
            peers = peerIdentities,
            contacts = contacts,
        )
    }

    /** Screen-share quality in `shroud.preferences`; "Always relay calls" read from `SecurityPreferences`. */
    val preferences: CallPreferences by lazy {
        CallPreferences(
            prefs = app.getSharedPreferences(PrefsFiles.PREFERENCES, Context.MODE_PRIVATE),
            seal = container.storageSeal,
            security = container.keys.securityPreferences,
        )
    }

    /** Microphone and camera; the call UI (W3-CALLS-UI) sets `prompt` to show the system dialog. */
    val permissions: AndroidCallPermissions by lazy { AndroidCallPermissions(app) }

    private val controllerLazy = lazy {
        CallController(
            scope = container.appScope,
            backend = ShroudCallsBackend(container.net.api),
            socket = RealtimeCallSocket(container.realtime.client),
            session = container.auth.sessionController.session,
            peers = ContactsCallPeers(secrets, peerIdentities, contacts),
            preferences = preferences,
            permissions = permissions,
            clock = container.clock,
            onCallEnded = { container.realtime.foregroundCoordinator.onCallEnded() },
            deviceNoun = { DeviceNoun.current(app) },
            screenCaptureSupported = { app.getSystemService(MediaProjectionManager::class.java) != null },
        )
    }

    /** The one call controller; built on the main thread (its state is main-confined). */
    val controller: CallController by controllerLazy

    /** [controller] when something already built it; the wipe stops what exists and builds nothing. */
    val controllerIfBuilt: CallController? get() = if (controllerLazy.isInitialized()) controller else null

    private var started: Job? = null

    /** Builds the controller (socket rings are heard from now on), keeps secrets current, follows the app phase. */
    override fun onProcessStart() {
        if (started != null) return
        val controller = controller
        val scope = container.appScope
        started = scope.launch {
            secrets.start(this, container.keys.cryptoController.unlockedUserId.map { it != null })
            container.appPhase.phase
                .map { it != AppPhase.Background }
                .distinctUntilChanged()
                .collect { visible -> controller.onAppVisible(visible) }
        }
    }

    /** Log Out / removal: the call screen and history go, a joined call is hung up, every call secret is deleted. */
    fun wipe() {
        controller.clearLocalState()
        secrets.deleteAll()
    }

    companion object {
        const val SECRETS_FILE = "call-secrets.sealed"
        const val SECRETS_ALIAS = "shroud.call-secrets.v1"
    }
}
