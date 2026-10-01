package de.corespace.shroud.di

import android.content.Context
import android.content.SharedPreferences
import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.contacts.PeerIdentities
import de.corespace.shroud.core.media.ImagePipeline
import de.corespace.shroud.core.media.LocalMediaStore
import de.corespace.shroud.core.media.MediaTransfers
import de.corespace.shroud.core.media.VideoPipeline
import de.corespace.shroud.core.messaging.MediaHydrator
import de.corespace.shroud.core.messaging.MediaLoader
import de.corespace.shroud.core.messaging.MessagingStore
import de.corespace.shroud.core.messaging.ReactionsEngine
import de.corespace.shroud.core.messaging.SendApi
import de.corespace.shroud.core.messaging.SendDependencies
import de.corespace.shroud.core.messaging.SendEngine
import de.corespace.shroud.core.messaging.SendHost
import de.corespace.shroud.core.messaging.SendKeyring
import de.corespace.shroud.core.messaging.SendPipeline
import de.corespace.shroud.core.messaging.ThreadState
import de.corespace.shroud.core.messaging.reactions.ReactionEngine
import de.corespace.shroud.core.notifications.MessageNotifier
import de.corespace.shroud.core.storage.PrefsFiles

/**
 * Send pipelines (00-plan §1.7.7). Owner: W2-MSG-SEND — [SendPipeline], [MediaHydrator],
 * [ReactionEngine] and the `shroud.messaging` preferences (`shroud.reactions.maxPerUser`).
 *
 * `MessagingModule` (W2-MSG-CORE) builds the one `MessagingController` and its `ThreadStore`, then
 * calls [send], [reactions] and [media] once each with that `ThreadState`. Nobody else constructs this
 * package's classes (00-plan §2.0 rule 3).
 *
 * **Same-wave ports.** The engines need objects of packages built in parallel with this one; until
 * W2-INT merges them these providers fail on first use with the name of the missing owner (nothing
 * calls them before W2-INT: `MessagingController` arrives in the same merge). W2-INT points each at
 * its owner's module:
 * - [store] → `MessagingStoreModule.store` (W2-MSG-STORE);
 * - [mediaStore], [transfers] → the `LocalMediaCache` and `MediaTransferService` of `MediaModule` (W2-MEDIA-STORE);
 * - [images] → `ImageModule`'s `ImageEncoder` (W2-MEDIA-IMAGE);
 * - [video] → `VideoModule`'s `VideoMedia`, and [videoTooLarge] → its "too large" error (W2-VIDEO, CR-4);
 * - [peerIdentities] → `ContactsModule`'s `PeerIdentityController` (W2-CONTACTS);
 * - [notifier] → `NotificationsModule`'s `NotificationsController` (W2-NOTIF);
 * - [host] → a [SendHost] over W2-MSG-CORE's `ThreadStore`, whose `ThreadState` additions carry the
 *   same members (CR-1); until then [SendHost.Detached] (no list refreshes, no reaction badges).
 *
 * `MessagingController` registers [media]'s result as an artifact sink (it is a
 * `MessageArtifactSinks`: purges and locks stop downloads), and lets the store write threads through
 * [ReactionEngine.settled].
 */
class MessagingSendModule(container: AppContainer) : AppModule(container) {
    @Volatile var host: (ThreadState) -> SendHost = { SendHost.Detached }
    @Volatile var store: () -> MessagingStore = { unwired("MessagingStore (W2-MSG-STORE)") }
    @Volatile var mediaStore: () -> LocalMediaStore = { unwired("LocalMediaStore (W2-MEDIA-STORE)") }
    @Volatile var transfers: () -> MediaTransfers = { unwired("MediaTransfers (W2-MEDIA-STORE)") }
    @Volatile var images: () -> ImagePipeline = { unwired("ImagePipeline (W2-MEDIA-IMAGE)") }
    @Volatile var video: () -> VideoPipeline = { unwired("VideoPipeline (W2-VIDEO)") }
    @Volatile var peerIdentities: () -> PeerIdentities = { unwired("PeerIdentities (W2-CONTACTS)") }
    @Volatile var notifier: () -> MessageNotifier? = { null }
    @Volatile var videoTooLarge: (Throwable) -> Boolean = { false }

    /** The REST slice of the engines, on the process's one `ShroudApi` (W1-NET). */
    val api: SendApi by lazy { SendApi.of(container.net.api) }

    /** Our identity keys through the one `CryptoController` (W1-KEYS). */
    val keyring: SendKeyring by lazy { SendKeyring.of(container.keys.cryptoController) }

    /** `shroud.messaging`: the reaction limit (00-plan §1.5; wiped at Log Out). */
    val preferences: SharedPreferences by lazy { container.appContext.getSharedPreferences(PrefsFiles.MESSAGING, Context.MODE_PRIVATE) }

    /** The send paths for [state] (the [SendEngine] seam). */
    fun send(state: ThreadState): SendEngine = SendPipeline(state, { host(state) }, dependencies())

    /** Reactions for [state] (the [ReactionsEngine] seam). */
    fun reactions(state: ThreadState): ReactionsEngine =
        ReactionEngine(state, { host(state) }, dependencies(), preferences, container.storageSeal)

    /** Media downloads for [state] (the [MediaLoader] seam); also an artifact sink to register. */
    fun media(state: ThreadState): MediaLoader = MediaHydrator(state, dependencies())

    private fun dependencies() = SendDependencies(
        api = api,
        keyring = keyring,
        crypto = container.keys.messageCrypto,
        peerLocks = container.keys.peerLocks,
        peerIdentities = { peerIdentities() },
        store = { store() },
        media = { mediaStore() },
        transfers = { transfers() },
        images = { images() },
        video = { video() },
        isOnline = { container.net.connectivity.isOnline.value },
        clock = container.clock,
        scope = container.appScope,
        notifier = { notifier() },
        videoTooLarge = { videoTooLarge(it) },
    )

    private fun unwired(what: String): Nothing = throw IllegalStateException("$what is not wired into MessagingSendModule yet (W2-INT).")
}
