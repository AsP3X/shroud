package de.corespace.shroud.ui.media.video

import android.graphics.Bitmap
import android.net.Uri
import de.corespace.shroud.AppContainer
import de.corespace.shroud.core.media.video.ChatVideoPlayer
import kotlinx.coroutines.flow.Flow

/**
 * What the video compose screen reads from core (R4: UI-side port, forwarded 1:1 by
 * [ContainerVideoComposeServices]). Tests pass a fake.
 */
internal interface VideoComposeServices {
    /** One preview player for the screen (`video.newPlayer()`, K1); torn down when it leaves. */
    fun newPlayer(): ChatVideoPlayer

    /** Evenly spaced stills for the trim strip (`video.media.filmstrip`, K1). */
    fun filmstrip(uri: Uri, count: Int): Flow<Bitmap>

    /** JPEG bytes of a filmstrip tile or poster (`images.mediaImages.compressJpeg`); throws when it cannot encode. */
    fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray

    /** Who the clips go to, for the top bar: the open chat's name, or null (`messaging.controller`, K1). */
    fun recipientName(): String?
}

/** [VideoComposeServices] on the app's modules, 1:1 (R4). */
internal class ContainerVideoComposeServices(private val container: AppContainer) : VideoComposeServices {
    override fun newPlayer(): ChatVideoPlayer = container.video.newPlayer()

    override fun filmstrip(uri: Uri, count: Int): Flow<Bitmap> = container.video.media.filmstrip(uri, count)

    override fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray = container.images.mediaImages.compressJpeg(bitmap, quality)

    override fun recipientName(): String? {
        val messaging = container.messaging.controller
        return messaging.activePeerId.value?.let(messaging::username)
    }
}
