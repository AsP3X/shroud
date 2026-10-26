package de.corespace.shroud.di

import android.content.Context
import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.contacts.ContactNameBook
import de.corespace.shroud.core.contacts.Contacts
import de.corespace.shroud.core.contacts.ContactsBackend
import de.corespace.shroud.core.contacts.ContactsController
import de.corespace.shroud.core.contacts.MutualContactNames
import de.corespace.shroud.core.contacts.PeerIdentities
import de.corespace.shroud.core.contacts.PeerIdentityController
import de.corespace.shroud.core.contacts.Privacy
import de.corespace.shroud.core.contacts.PrivacyController
import de.corespace.shroud.core.contacts.ShroudContactsBackend
import de.corespace.shroud.core.contacts.agreementKeys
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.storage.KeystoreSealer
import de.corespace.shroud.core.storage.PrefsFiles
import de.corespace.shroud.core.storage.SealedFile
import java.io.File

/**
 * Contacts, peer identity, privacy (00-plan §1.7.8, C6). Owner: W2-CONTACTS. Nobody else constructs
 * these classes (00-plan §2.0 rule 3): other packages reach them through this module.
 *
 * - [controller] — the roster, requests, presence, blocks, add/accept/reject, the App Link invite
 *   (`Contacts`). `MessagingController` binds itself as its `ContactsHooks` and drives its lifecycle
 *   (`hydrate`, `start`, `onForeground`, `onBackground`, `onConnectivityRegained`, `stop`); that
 *   lifecycle also refreshes / resets [privacy] and clears [peerIdentities]' memory (pins on a wipe),
 *   so messaging need not call those itself.
 * - [peerIdentities] — TOFU pins, key changes, verification, safety numbers (`PeerIdentities`); the
 *   messaging engines resolve keys through it, calls collect its `events`.
 * - [privacy] — the privacy switches and share-code rotation (`Privacy`); messaging collects its
 *   `settings` for the read-receipt and typing consequences.
 */
class ContactsModule(container: AppContainer) : AppModule(container) {
    private val session: () -> Session? = { container.auth.sessionController.session.value }

    private val backend: ContactsBackend by lazy { ShroudContactsBackend(container.net.api) }

    /** AFU, like the notification names: the roster also refreshes while the phone is locked. */
    private val nameSealer: KeystoreSealer by lazy { KeystoreSealer(ContactNameBook.KEY_ALIAS) }

    /**
     * `contact-names.sealed` in no-backup storage (security audit M9). Older builds kept the names
     * in `shroud.preferences`; the book moves them into the record and deletes those keys.
     */
    private val nameBookLazy = lazy {
        ContactNameBook(
            file = SealedFile(File(container.appContext.noBackupFilesDir, ContactNameBook.FILE_NAME), nameSealer),
            deleteKey = { nameSealer.deleteKey() },
            seal = container.storageSeal,
            legacy = container.appContext.getSharedPreferences(PrefsFiles.PREFERENCES, Context.MODE_PRIVATE),
        )
    }
    private val nameBook: ContactNameBook by nameBookLazy

    /**
     * `WipeHooks.forgetContactNames()`: the book's memory, record and key. A book never built holds
     * nothing in memory, and the wipe's sweep removes its file and key.
     */
    fun forgetNamesIfBuilt() {
        if (nameBookLazy.isInitialized()) nameBook.forget()
    }

    private val contactsController: ContactsController by lazy {
        ContactsController(
            backend = backend,
            session = session,
            events = container.realtime.client.events,
            scope = container.appScope,
            clock = container.clock,
            listeners = { listOf(privacyController, peerIdentityController) },
            names = MutualContactNames(
                book = nameBook,
                owner = { Ids.parse(session()?.userId) },
                username = { session()?.username },
                keys = { container.keys.cryptoController.withMaterial { it.agreementKeys() } },
                peerKey = { peerIdentityController.publicKeyForSending(it) },
                publish = { token, peer, sealed -> container.net.api.putContactName(token, peer, sealed) },
                historyKey = { container.keys.cryptoController.withMaterial { it.historyKey.copyOf() } },
                fetchBook = { token -> container.net.api.contactNames(token) },
                storeBook = { token, sealed, version -> container.net.api.putContactNames(token, sealed, version) },
            ),
        )
    }

    private val peerIdentityController: PeerIdentityController by lazy {
        PeerIdentityController(
            backend = backend,
            store = container.keys.peerIdentities,
            ratchets = container.keys.ratchetSessions,
            peerLocks = container.keys.peerLocks,
            token = { session()?.token },
            localIdentityPublicKey = { container.keys.cryptoController.withMaterial { it.identityPublicKey.copyOf() } },
            scope = container.appScope,
        )
    }

    private val privacyController: PrivacyController by lazy {
        PrivacyController(
            backend = backend,
            session = session,
            revalidate = { container.auth.sessionController.validate() },
            scope = container.appScope,
            onSharePresenceChanged = { contactsController.onSharePresenceChanged(it) },
            setLastError = { contactsController.reportLastError(it) },
        )
    }

    /** Reads the name book once, off the main thread, so the first roster does not wait for the Keystore. */
    override fun onProcessStart() {
        nameBook.preload()
    }

    /** The roster, requests, presence and blocks (`ContactsController`). */
    val controller: Contacts get() = contactsController

    /** Peer key pinning, changes, verification and safety numbers (`PeerIdentityController`). */
    val peerIdentities: PeerIdentities get() = peerIdentityController

    /** Privacy settings and the share code (`PrivacyController`). */
    val privacy: Privacy get() = privacyController
}
