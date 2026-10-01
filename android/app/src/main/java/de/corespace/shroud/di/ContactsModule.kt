package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule

/**
 * Contacts, peer identity, privacy (00-plan §1.7.8, C6). Owner: W2-CONTACTS —
 * `ContactsController`, `PeerIdentityController`, `PrivacyController`, `InviteLookup`.
 *
 * Created empty by W0-A; only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else
 * constructs this package's classes: other packages reach them through this module.
 */
class ContactsModule(container: AppContainer) : AppModule(container)
