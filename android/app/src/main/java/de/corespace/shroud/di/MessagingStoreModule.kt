package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule

/**
 * Sealed local message store (00-plan §1.5, §1.7.7). Owner: W2-MSG-STORE — `LocalMessageStore`,
 * `LocalPlaintextCache`, `MessagingLocalRepository` under `noBackupFilesDir/shroud/messages` and
 * `shroud/plaintext`, sealed under `historyKey` subkeys with `LocalNames` file names.
 *
 * Created empty by W0-A; only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else
 * constructs this package's classes: other packages reach them through this module.
 */
class MessagingStoreModule(container: AppContainer) : AppModule(container)
