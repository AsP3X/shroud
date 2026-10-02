package de.corespace.shroud.ui.calls

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import de.corespace.shroud.ui.theme.ShroudTheme
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One WebRTC video track on a [TextureView], drawn by `org.webrtc`'s [EglRenderer] with the
 * engine's shared EGL context (iOS `RTCMTLVideoView` in `CallVideoView`; calls §8.6).
 *
 * A `SurfaceViewRenderer` draws on its own surface, which the window composes outside the view
 * tree: it cannot be clipped to the face reveal's circle or the tiles' rounded corners, nor
 * scaled for zoom, nor faded. A texture view composes like any view, so this is the
 * `SurfaceViewRenderer`'s own [EglRenderer] on a `SurfaceTexture`.
 *
 * Fill (crop) by default: the layout aspect ratio is the view's. [matchBufferToFrame] (their
 * shared screen) sizes the texture to the frame instead, so a zoom samples the source pixels and
 * stays sharp (calls §8.9).
 */
@SuppressLint("ViewConstructor")
internal class CallTextureRenderer(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val renderer = EglRenderer(RENDERER_NAME)
    private var initialized = false
    private var track: VideoTrack? = null
    private var frameWidth = 0
    private var frameHeight = 0

    /** The frame's rotated size changed; called on the main thread. */
    var onFrameSize: ((IntSize) -> Unit)? = null

    /** Size the texture to the frame (their shared screen) rather than to the view. */
    var matchBufferToFrame = false

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    /** Starts drawing [newTrack] once an EGL context exists; replaces the track drawn before. */
    fun attach(eglContext: EglBase.Context?, newTrack: VideoTrack?) {
        if (!initialized && eglContext != null) {
            renderer.init(eglContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
            initialized = true
            surfaceTexture?.let { renderer.createEglSurface(it) }
            track?.addSink(this)
        }
        if (track === newTrack) return
        track?.removeSink(this)
        track = newTrack
        if (initialized) newTrack?.addSink(this)
    }

    fun setMirror(mirror: Boolean) {
        renderer.setMirror(mirror)
    }

    /** A closed reveal stops drawing (iOS `video.isEnabled = false`); [paused] false draws every frame again. */
    fun setPaused(paused: Boolean) {
        if (paused) renderer.pauseVideo() else renderer.disableFpsReduction()
    }

    fun release() {
        track?.removeSink(this)
        track = null
        if (initialized) renderer.release()
        initialized = false
    }

    override fun onFrame(frame: VideoFrame) {
        val width = frame.rotatedWidth
        val height = frame.rotatedHeight
        if (width != frameWidth || height != frameHeight) {
            frameWidth = width
            frameHeight = height
            post {
                applyLayout()
                onFrameSize?.invoke(IntSize(width, height))
            }
        }
        renderer.onFrame(frame)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyLayout()
    }

    private fun applyLayout() {
        if (matchBufferToFrame && frameWidth > 0 && frameHeight > 0) {
            surfaceTexture?.setDefaultBufferSize(frameWidth, frameHeight)
            renderer.setLayoutAspectRatio(frameWidth.toFloat() / frameHeight)
        } else if (width > 0 && height > 0) {
            renderer.setLayoutAspectRatio(width.toFloat() / height)
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (initialized) renderer.createEglSurface(surface)
        applyLayout()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyLayout()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (!initialized) return true
        // The texture is released once this returns: the render thread lets go of it first.
        val released = CountDownLatch(1)
        renderer.releaseEglSurface { released.countDown() }
        released.await(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private companion object {
        const val RENDERER_NAME = "shroud-call-video"
        const val RELEASE_WAIT_MS = 500L
    }
}

/**
 * [track] in a [CallTextureRenderer]. [eglContext] is read when the view needs it, so the engine
 * is reached only once a picture exists. Hidden from TalkBack: the screen labels its pictures.
 */
@Composable
internal fun CallVideo(
    track: VideoTrack,
    eglContext: () -> EglBase.Context?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    paused: Boolean = false,
    matchBufferToFrame: Boolean = false,
    onFrameSize: ((IntSize) -> Unit)? = null,
) {
    val currentOnFrameSize by rememberUpdatedState(onFrameSize)
    AndroidView(
        factory = { context -> CallTextureRenderer(context) },
        modifier = modifier.clearAndSetSemantics {},
        onRelease = { it.release() },
        update = { view ->
            view.matchBufferToFrame = matchBufferToFrame
            view.onFrameSize = { size -> currentOnFrameSize?.invoke(size) }
            view.attach(eglContext(), track)
            view.setMirror(mirror)
            view.setPaused(paused)
        },
    )
}

/**
 * Where the face is, in root coordinates, as layout last measured it (iOS `FaceSpot`,
 * `CallVideoView.swift:62-68`): a plain holder, so measuring the face never recomposes the screen;
 * the reveal reads it when its circle starts to move.
 */
internal class FaceSpot {
    var boundsInRoot: Rect = Rect.Zero
}

/** The face reveal's timing (`CallVideoContainer`, `CallVideoView.swift:82-89`). */
internal object FaceRevealSpec {
    const val OPEN_MS = 420
    const val CLOSE_MS = 380

    /** Moves from the first frame and slows evenly (`CAMediaTimingFunction(0.33, 1, 0.68, 1)`). */
    val easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
}

/**
 * Their camera, full screen, opening out of their face as a growing circle and closing back into
 * it (iOS `CallVideoView` with `reveal`, `CallVideoView.swift:78-227`; calls §8.6).
 *
 * The circle is a clip on the view's own layer, centred on the face; the radius animates in the
 * draw phase only (no recomposition, no path redrawn per frame). Open at rest: no clip at all.
 * Closed at rest: not drawn, and the renderer paused unless [warm] (their camera is on, so the
 * circle never opens onto an old frame). Reduce motion, or nothing measured yet: the rest state at once.
 */
@Composable
internal fun RevealedVideo(
    track: VideoTrack,
    eglContext: () -> EglBase.Context?,
    open: Boolean,
    warm: Boolean,
    face: FaceSpot,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = ShroudTheme.reduceMotion
    val radius = remember { Animatable(0f) }
    var animating by remember { mutableStateOf(false) }
    var restOpen by remember { mutableStateOf(open) }
    val geometry = remember { RevealGeometry() }
    var first by remember { mutableStateOf(true) }

    LaunchedEffect(open) {
        if (first || reduceMotion || geometry.size.width <= 0f) {
            first = false
            restOpen = open
            animating = false
            return@LaunchedEffect
        }
        val center = geometry.faceCenter(face)
        val faceRadius = CallStageGeometry.faceRadius(face.boundsInRoot.width)
        val full = CallStageGeometry.fullRadius(center, geometry.size)
        // Turned round mid-way (a newer change cancelled this one), the circle goes on from where it is.
        if (!animating) radius.snapTo(if (open) faceRadius else full)
        animating = true
        restOpen = false
        radius.animateTo(
            if (open) full else faceRadius,
            tween(if (open) FaceRevealSpec.OPEN_MS else FaceRevealSpec.CLOSE_MS, easing = FaceRevealSpec.easing),
        )
        restOpen = open
        animating = false
    }
    val closedAtRest = !open && !animating
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                geometry.origin = coordinates.positionInRoot()
                geometry.size = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
            }
            .graphicsLayer {
                if (animating) {
                    val center = geometry.faceCenter(face)
                    clip = true
                    shape = CircleClip(center, radius.value)
                    alpha = 1f
                } else {
                    clip = false
                    alpha = if (restOpen) 1f else 0f
                }
            },
    ) {
        CallVideo(
            track = track,
            eglContext = eglContext,
            modifier = Modifier.fillMaxSize(),
            paused = closedAtRest && !warm,
        )
    }
}

/** The reveal's measured box, read while drawing. */
private class RevealGeometry {
    var origin: Offset = Offset.Zero
    var size: Size = Size.Zero

    /** The face's centre in this box; the box's middle before the face was measured. */
    fun faceCenter(face: FaceSpot): Offset {
        val bounds = face.boundsInRoot
        if (bounds.width <= 0f) return Offset(size.width / 2f, size.height / 2f)
        return bounds.center - origin
    }
}

/** A circle of [radius] round [center]: a rounded-rect outline, which a layer clips cheaply. */
private class CircleClip(private val center: Offset, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(
            RoundRect(
                left = center.x - radius,
                top = center.y - radius,
                right = center.x + radius,
                bottom = center.y + radius,
                cornerRadius = CornerRadius(radius, radius),
            ),
        )
}
