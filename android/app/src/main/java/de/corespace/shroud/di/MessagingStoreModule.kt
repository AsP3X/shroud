package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.media.LocalMediaStore
import de.corespace.shroud.core.messaging.MessagingStore
import de.corespace.shroud.core.messaging.local.MessagingLocalRepository

/**
 * Sealed local message store (00-plan §1.5, §1.7.7). Owner: W2-MSG-STORE — `LocalMessageStore`,
 * `LocalPlaintextCache`, `MessagingLocalRepository` under `noBackupFilesDir/shroud/messages` and
 * `shroud/plaintext`, sealed under `historyKey` subkeys with `LocalNames` file names, following the
 * chat lock through `KeysModule.sealedLocalState`.
 *
 * Only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else constructs this package's
 * classes: other packages reach them through [store].
 */
class MessagingStoreModule(container: AppContainer) : AppModule(container) {
    /**
     * The SHRM1 media cache (W2-MEDIA-STORE's `LocalMediaStore`), asked on every call: hydrate reads
     * which messages have their media on the phone, purges and [MessagingStore.clear] remove media
     * (wired by W2-INT; tests may replace it).
     */
    @Volatile
    var localMedia: () -> LocalMediaStore? = { container.media.localMedia }

    /** The one sealed message store of the process (plan §1.7.7 `MessagingStore`). Blocking: call off main. */
    val store: MessagingStore by lazy {
        MessagingLocalRepository(
            root = container.appContext.noBackupFilesDir,
            state = container.keys.sealedLocalState,
            storageSeal = container.storageSeal,
            clock = container.clock,
            localMedia = { localMedia() },
        )
    }
}
