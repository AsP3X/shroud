package de.corespace.shroud.di

import de.corespace.shroud.AppContainer
import de.corespace.shroud.AppModule
import de.corespace.shroud.core.media.ImageEncoder
import de.corespace.shroud.core.media.ImagePipeline
import de.corespace.shroud.core.media.MediaEditBaker
import de.corespace.shroud.core.media.MediaImages

/**
 * Image encoding and metadata scrubbing (00-plan §1.7.9). Owner: W2-MEDIA-IMAGE — [ImageEncoder],
 * [MediaImages], the scrubbers in `core/media/scrub` (pure, used through the encoder).
 *
 * Only the owner fills this module (00-plan §2.0 rule 3, §2.6). Nobody else constructs this
 * package's classes: other packages reach them through here —
 * - messaging's photo send ([ImagePipeline], W2-MSG-SEND) through [pipeline];
 * - the compose screen and the viewer (`decodePreview`, W3) through [mediaImages].
 *
 * Edits are baked through the [MediaEditBaker] registered with [registerEditBaker] — the identity
 * until W3-MEDIA-EDIT's `MediaEditRenderer` is registered (W3-INT wires it).
 */
class ImageModule(container: AppContainer) : AppModule(container) {
    @Volatile
    private var editBaker: MediaEditBaker = MediaEditBaker.Identity

    /** Decoding, previews and JPEG compression on the platform decoder. */
    val mediaImages: MediaImages by lazy { MediaImages(container.appContext.contentResolver) }

    /** The photo encoder: passthrough with scrubbed metadata, or a fresh JPEG. */
    val imageEncoder: ImageEncoder by lazy { ImageEncoder(mediaImages, editBaker = { editBaker }) }

    /** The seam messaging sends photos through (00-plan §1.7.9). */
    val pipeline: ImagePipeline get() = imageEncoder

    /** Makes [baker] bake every later send's edits (W3-MEDIA-EDIT's renderer). */
    fun registerEditBaker(baker: MediaEditBaker) {
        editBaker = baker
    }
}
