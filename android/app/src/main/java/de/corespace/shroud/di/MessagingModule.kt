package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule

/**
 * Messaging engine (00-plan §1.7.7, C6). Owner: W2-MSG-CORE — the one `MessagingController`
 * (chats, threads, sends, reads, mutes, reactions, typing, deletes) and its engines
 * (`ThreadStore`, `HistoryPager`, `MessageDecoder`, `DeleteEngine`, `ReadStateEngine`, …).
 *
 * Created empty by W0-A; only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else
 * constructs this package's classes: other packages reach them through this module.
 */
class MessagingModule(container: AppContainer) : AppModule(container)
