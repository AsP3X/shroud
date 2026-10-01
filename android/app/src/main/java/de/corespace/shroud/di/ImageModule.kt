package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule

/**
 * Image encoding and metadata scrubbing (00-plan §1.7.9). Owner: W2-MEDIA-IMAGE —
 * `ImageEncoder`, `MediaImages`, the scrubbers in `core/media/scrub`.
 *
 * Created empty by W0-A; only the owner fills it (00-plan §2.0 rule 3, §2.6). Nobody else
 * constructs this package's classes: other packages reach them through this module.
 */
class ImageModule(container: AppContainer) : AppModule(container)
