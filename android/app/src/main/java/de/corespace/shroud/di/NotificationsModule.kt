package de.corespace.shroud.di

import android.content.Context
import android.graphics.Bitmap
import androidx.core.app.NotificationManagerCompat
import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.notifications.AndroidAccessibilityState
import de.corespace.shroud.core.notifications.AndroidChannelStore
import de.corespace.shroud.core.notifications.AndroidNotificationSink
import de.corespace.shroud.core.notifications.NotificationAuthorization
import de.corespace.shroud.core.notifications.NotificationChannels
import de.corespace.shroud.core.notifications.NotificationContentKey
import de.corespace.shroud.core.notifications.NotificationNameCache
import de.corespace.shroud.core.notifications.NotificationPermission
import de.corespace.shroud.core.notifications.NotificationPreferences
import de.corespace.shroud.core.notifications.NotificationSoundPlayer
import de.corespace.shroud.core.notifications.NotificationsController
import de.corespace.shroud.core.notifications.PushDeliveryHooks
import de.corespace.shroud.core.notifications.SystemNotifier
import de.corespace.shroud.core.storage.KeystoreSealer
import de.corespace.shroud.core.storage.PrefsFiles
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.ui.components.AvatarBitmap
import de.corespace.shroud.ui.components.AvatarPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Notifications (00-plan §1.7.10, C17, C18). Owner: W2-NOTIF — [controller] (the `MessageNotifier`
 * messaging calls), [preferences], [channels], [systemNotifier], [nameCache], [soundPlayer] and
 * [permission]. The channels are ensured and the name cache preloaded in [onProcessStart].
 *
 * Wiring the other packages do through this module (they never construct these classes, 00-plan
 * §2.0 rule 3):
 * - messaging (W2-INT): `MessagingDeps.notifier = container.notifications.controller`;
 * - the wipe (W2-INT): `WipeHooks.forgetNotifications()` → [NotificationsController.forgetAccount];
 * - the shell (W2-INT interim, then W3-SHELL): `isUnlocked` / `isSignedIn`, `refreshAuthorization`
 *   on resume, `banner`, `haptics`, `pendingOpen`, and `MainActivity` intents through
 *   `NotificationTap.from(intent)` → `handleTap`;
 * - push (W3-PUSH): [pushHooks] (`PushModule.deliveryHooks`, wired by W2-INT), `SystemNotifier.post`, `onPushWhileRunning`, [nameCache] on the
 *   socket path, `channels.backgroundConnection()` / `backgroundTasks()`.
 */
class NotificationsModule(container: AppContainer) : AppModule(container) {
    private val app: Context get() = container.appContext

    /** `notifications.*` in `shroud.notifications` (00-plan §1.5; wiped at Log Out). */
    val preferences: NotificationPreferences by lazy {
        NotificationPreferences(app.getSharedPreferences(PrefsFiles.NOTIFICATIONS, Context.MODE_PRIVATE), container.storageSeal)
    }

    /** `POST_NOTIFICATIONS` and the "asked once" flag in `shroud.device` (kept at Log Out, N16). */
    val permission: NotificationPermission by lazy {
        NotificationPermission(app, app.getSharedPreferences(PrefsFiles.DEVICE, Context.MODE_PRIVATE))
    }

    val channels: NotificationChannels by lazy { NotificationChannels(AndroidChannelStore(app)) { preferences.state.value } }

    val soundPlayer: NotificationSoundPlayer by lazy { NotificationSoundPlayer(app) }

    /**
     * The history key for Show Content, sealed AFU. Absent until the switch is on and the chats
     * have been unlocked once. Log Out deletes it ([NotificationsController.forgetAccount]).
     */
    val contentKey: NotificationContentKey by lazy {
        val sealer = KeystoreSealer(CONTENT_KEY_ALIAS, unlockedDeviceRequired = false)
        NotificationContentKey(
            file = SealedFile(File(app.noBackupFilesDir, CONTENT_KEY_FILE), sealer),
            seal = container.storageSeal,
            deleteKey = { sealer.deleteKey() },
        )
    }

    val systemNotifier: SystemNotifier by lazy {
        SystemNotifier(AndroidNotificationSink(app, ::avatar), channels, container.storageSeal)
    }

    /** AFU (P3a): readable while the phone is locked, when the background connection announces. */
    private val nameSealer: KeystoreSealer by lazy { KeystoreSealer(NotificationNameCache.KEY_ALIAS) }

    /** `notification-names.sealed` in no-backup storage (00-plan §1.5). */
    val nameCache: NotificationNameCache by lazy {
        NotificationNameCache(
            file = SealedFile(File(app.noBackupFilesDir, NotificationNameCache.FILE_NAME), nameSealer),
            deleteKey = { nameSealer.deleteKey() },
            seal = container.storageSeal,
            namesOn = { preferences.showSender },
        )
    }

    /** The push layer's re-registration and "no delivery" copy for the test (`PushModule.deliveryHooks`, W3-PUSH). */
    @Volatile
    var pushHooks: () -> PushDeliveryHooks? = { container.push.deliveryHooks }

    /** The process's one notifications controller (iOS `NotificationsController.shared`). Main-confined. */
    val controller: NotificationsController by lazy {
        NotificationsController(
            preferences = preferences,
            permission = object : NotificationsController.Permission {
                override fun current(): NotificationAuthorization = permission.current(container.appPhase.topActivity)
                override val wasRequested: Boolean get() = permission.wasRequested
                override fun markRequested() = permission.markRequested()
            },
            sounds = soundPlayer,
            systemNotifier = systemNotifier,
            channels = channels,
            nameCache = nameCache,
            isResumed = { container.appPhase.isResumed },
            accessibility = AndroidAccessibilityState(app),
            api = { container.net.api },
            systemAllows = { NotificationManagerCompat.from(app).areNotificationsEnabled() },
            clock = container.clock,
            scope = container.appScope,
            pushHooks = { pushHooks() },
            onForgotten = { runCatching { contentKey.clear() } },
        )
    }

    /**
     * Channels for the current preferences (notifications-push §5.6, §5.19); names ready for the
     * first announcement. A process woken by a push has no activity, so the shell never sets
     * [NotificationsController.isSignedIn]; without a session here, [NotificationsController.onPushWhileRunning]
     * would swallow the system notification. The shell still overrides it while it is on screen.
     */
    override fun onProcessStart() {
        runCatching { channels.ensure() }
        nameCache.preload()
        val notifications = controller
        val sessions = container.auth.sessionController.session
        notifications.isSignedIn = sessions.value != null
        container.appScope.launch {
            sessions.collect { notifications.isSignedIn = it != null }
        }
        container.appScope.launch { keepContentKey() }
    }

    /**
     * Show Content on, and the chats unlocked: seal the history key for later notifications.
     * Off: delete that copy. Locking the chats does not delete it.
     */
    private suspend fun keepContentKey() {
        combine(
            preferences.state.map { it.showContent }.distinctUntilChanged(),
            container.keys.cryptoController.unlockedUserId,
        ) { on, user -> on to user }.collect { (on, user) ->
            if (!on) {
                withContext(Dispatchers.IO) { runCatching { contentKey.clear() } }
                return@collect
            }
            if (user == null) return@collect
            val history = container.keys.cryptoController.withMaterial { it.historyKey.copyOf() } ?: return@collect
            try {
                withContext(Dispatchers.IO) { runCatching { contentKey.save(history) } }
            } finally {
                history.fill(0)
            }
        }
    }

    /** The 40 dp gradient avatar of the shade (design *Notifications — Shade*), seeded like the app's (P15). */
    private fun avatar(name: String, peerUserId: UUID?): Bitmap? = runCatching {
        AvatarBitmap.render(app, AvatarPalette.seed(name, peerUserId ?: NO_ID), AvatarPalette.initials(name))
    }.getOrNull()

    private companion object {
        val NO_ID: UUID = UUID(0, 0)
        const val CONTENT_KEY_ALIAS = "shroud.notification-content.v1"
        const val CONTENT_KEY_FILE = "notification-content-key.sealed"
    }
}
