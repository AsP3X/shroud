package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule

/**
 * Send pipelines (00-plan §1.7.7). Owner: W2-MSG-SEND — `SendPipeline`, `MediaHydrator`,
 * `ReactionEngine` and the `shroud.messaging` preferences (`shroud.reactions.maxPerUser`).
 *
 * Created empty by W0-A; only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else
 * constructs this package's classes: other packages reach them through this module.
 */
class MessagingSendModule(container: AppContainer) : AppModule(container)
