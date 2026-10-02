package de.corespace.shroud.ui.media.video

import androidx.media3.common.Player
import de.corespace.shroud.core.media.video.ChatVideoPlayer
import de.corespace.shroud.core.media.video.PlaybackEngine
import de.corespace.shroud.core.media.video.VideoSource
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A scripted engine: ready at once (or never), no surface, playing when told. */
internal class ScriptedEngine(override var durationSeconds: Double = 10.0, ready: Boolean = true) : PlaybackEngine {
    override val player: Player? = null
    override val status: StateFlow<PlaybackEngine.Status> =
        MutableStateFlow(if (ready) PlaybackEngine.Status.Ready else PlaybackEngine.Status.Failed)
    override var positionSeconds = 0.0
    override var isPlaying = false
    override var onEnded: (() -> Unit)? = null
    var volume = 1f
    var released = false

    override fun play() {
        isPlaying = true
    }

    override fun pause() {
        isPlaying = false
    }

    override fun seekTo(seconds: Double, precise: Boolean) {
        positionSeconds = seconds
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun release() {
        released = true
    }
}

/** Hands out [ScriptedEngine]s (or null for a source that cannot be read) and records the sources. */
internal class ScriptedEngines(private val make: (VideoSource) -> PlaybackEngine?) : PlaybackEngine.Factory {
    val sources = ArrayList<VideoSource>()
    val engines = ArrayList<PlaybackEngine>()

    override fun create(source: VideoSource): PlaybackEngine? {
        sources += source
        return make(source)?.also { engines += it }
    }

    fun player(): ChatVideoPlayer = ChatVideoPlayer(this, MainScope())
}
