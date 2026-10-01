package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule

/**
 * Links (00-plan §1.7.9, C2, C32). Owner: W2-LINKS — `LinkDetector`, `LinkPreviewFetcher`,
 * `LinkPreviewComposer`, `LinkOpener` (Custom Tabs in the user's own browser).
 *
 * Created empty by W0-A; only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else
 * constructs this package's classes: other packages reach them through this module.
 */
class LinksModule(container: AppContainer) : AppModule(container)
