package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.contacts.Contacts
import de.corespace.shroud.core.contacts.ContactsBackend
import de.corespace.shroud.core.contacts.ContactsController
import de.corespace.shroud.core.contacts.PeerIdentities
import de.corespace.shroud.core.contacts.PeerIdentityController
import de.corespace.shroud.core.contacts.Privacy
import de.corespace.shroud.core.contacts.PrivacyController
import de.corespace.shroud.core.contacts.ShroudContactsBackend

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

    private val contactsController: ContactsController by lazy {
        ContactsController(
            backend = backend,
            session = session,
            events = container.realtime.client.events,
            scope = container.appScope,
            clock = container.clock,
            listeners = { listOf(privacyController, peerIdentityController) },
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

    /** The roster, requests, presence and blocks (`ContactsController`). */
    val controller: Contacts get() = contactsController

    /** Peer key pinning, changes, verification and safety numbers (`PeerIdentityController`). */
    val peerIdentities: PeerIdentities get() = peerIdentityController

    /** Privacy settings and the share code (`PrivacyController`). */
    val privacy: Privacy get() = privacyController
}
