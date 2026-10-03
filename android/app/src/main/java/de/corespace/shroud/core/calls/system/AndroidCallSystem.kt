package de.corespace.shroud.core.calls.system

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ServiceCompat
import de.corespace.shroud.core.calls.CallAudioRoute
import de.corespace.shroud.core.calls.CallAudioRouteType
import de.corespace.shroud.core.calls.CallEndCause
import de.corespace.shroud.core.calls.CallSystem
import de.corespace.shroud.core.calls.CallTexts
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.notifications.SystemNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.UUID

/**
 * Telecom, the phoneCall foreground service, CallStyle notifications, the ringer, the audio
 * route and the proximity lock (calls §6).
 *
 * A background ring posts the incoming notification first (it carries a full-screen intent),
 * then adds the Telecom call when Telecom is available, then starts [CallService]. On Android 15
 * and later a CallStyle notification without that intent is posted by the service: notifying it
 * directly throws and kills the process. A refused foreground start leaves a notification that
 * was already posted still ringing.
 * Session fields are touched on the main thread. Nothing here logs a caller name.
 */
internal class AndroidCallSystem(
    private val context: Context,
    private val notices: CallNotices,
    private val shade: CallShade,
    private val starter: CallForegroundStarter,
    private val telecom: CallTelecom,
    private val ringer: CallRinger,
    private val audio: CallAudio,
    private val proximity: ProximitySensor,
    private val callbacks: CallSystemCallbacks,
    private val missedChannelId: () -> String,
    private val appInForeground: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : CallSystem, CallScreenHooks {
    private val main = Handler(Looper.getMainLooper())
    private val earpiece = MutableStateFlow(true)
    private val phoneEarpiece = CallAudioRoute(CallAudioRouteType.Earpiece, "Earpiece", "earpiece")
    private val phoneSpeaker = CallAudioRoute(CallAudioRouteType.Speaker, "Speaker", "speaker")
    private val routes = MutableStateFlow(listOf(phoneEarpiece, phoneSpeaker))
    private val currentRouteFlow = MutableStateFlow<CallAudioRoute?>(phoneEarpiece)
    private var telecomRoutes: List<CallAudioRoute> = emptyList()
    private var telecomCurrent: CallAudioRoute? = null
    private val endedIds = ArrayDeque<UUID>()

    private var session: Session? = null
    private var speakerOn = false
    private var audioOn = false
    private var screenShown = false
    private var foregroundRefused = false
    private var promoted = false

    /** Types passed to the last successful [ServiceCompat.startForeground]. 0 when none is held. */
    private var foregroundTypes: Int = 0
    private var telecomActivated = false
    private var service: Service? = null
    private var startId = 0
    /** `startForegroundService` has returned and [onServiceStart] has not run yet. */
    private var foregroundStartPending = false
    /** The call ended during that window. Stopping now would crash the process. */
    private var stopAfterStart = false
    private var lastId: UUID? = null

    override val isOnEarpiece: StateFlow<Boolean> = earpiece
    override val audioRoutes: StateFlow<List<CallAudioRoute>> = routes
    override val currentRoute: StateFlow<CallAudioRoute?> = currentRouteFlow

    init {
        telecom.listener = object : CallTelecomListener {
            override fun onEarpiece(callId: UUID, earpiece: Boolean) = onMain {
                if (session?.id != callId) return@onMain
                this@AndroidCallSystem.earpiece.value = earpiece
                refreshProximity()
            }

            override fun onAnswer(callId: UUID) = onMain {
                acceptLocally(callId)
                callbacks.onAnswer(callId)
            }

            override fun onEnded(callId: UUID, cause: CallEndCause) = onMain {
                // Telecom already closed its call. The controller's from-kit end does not call back here.
                if (session?.id == callId) reportEnded(callId, cause)
                callbacks.onEnd(callId)
            }

            override fun onMute(callId: UUID, muted: Boolean) = onMain {
                callbacks.onMute(callId, muted)
            }

            override fun onUnavailable(callId: UUID) = onMain {
                val current = session ?: return@onMain
                if (current.id != callId || current.style == Style.Incoming || audioOn) return@onMain
                startAudio()
            }

            override fun onAudioRoutes(callId: UUID, routes: List<CallAudioRoute>, current: CallAudioRoute?) = onMain {
                if (session?.id != callId) return@onMain
                telecomRoutes = routes
                telecomCurrent = current
                if (current != null) earpiece.value = current.type == CallAudioRouteType.Earpiece
                publishRoutes()
                refreshProximity()
            }
        }
    }

    /** Channels and Telecom registration. [de.corespace.shroud.di.CallsSystemModule.onProcessStart] calls this. */
    fun start() {
        shade.ensureChannels()
        telecom.register()
    }

    override fun reportIncoming(callId: UUID, peerName: String, video: Boolean) {
        begin(callId, peerName, video, outgoing = false, style = Style.Incoming)
        postCurrent()
        ringer.start()
        val shown = session?.name ?: shownName(peerName)
        runCatching { telecom.add(callId, shown, video, outgoing = false) }
        if (telecom.tracksCall) telecom.setSpeaker(speakerOn)
        tryStartForeground()
        refreshProximity()
    }

    override fun reportOutgoing(callId: UUID, peerName: String, video: Boolean) {
        begin(callId, peerName, video, outgoing = true, style = Style.Outgoing)
        postCurrent()
        val shown = session?.name ?: shownName(peerName)
        runCatching { telecom.add(callId, shown, video, outgoing = true) }
        if (telecom.tracksCall) telecom.setSpeaker(speakerOn) else startAudio()
        tryStartForeground()
        refreshProximity()
    }

    override fun reportConnected(callId: UUID) {
        val current = session ?: return
        if (current.id != callId) return
        ringer.stop()
        current.style = Style.Ongoing
        if (current.connectedAt == null) current.connectedAt = clock()
        if (!telecomActivated && telecom.tracksCall) {
            telecomActivated = true
            telecom.setActive()
        }
        if (!telecom.tracksCall) startAudio()
        postCurrent()
        refreshProximity()
    }

    override fun reportEnded(callId: UUID, cause: CallEndCause) {
        val matching = session?.takeIf { it.id == callId }
        if (callId in endedIds && matching == null) {
            cancelBoth(callId)
            return
        }
        rememberEnded(callId)
        val outgoing = matching?.outgoing == true
        val name = matching?.name
        if (matching != null) stopAll(cause) else cancelBoth(callId)
        // reportEnded carries no peer id. postMissedCall does, and a local timeout still offers Call back.
        if (cause == CallEndCause.Missed && !outgoing) postMissed(callId, null, name)
    }

    override fun update(callId: UUID, peerName: String, video: Boolean) {
        val current = session ?: return
        if (current.id != callId) return
        current.name = shownName(peerName)
        current.video = video
        postCurrent()
        refreshProximity()
    }

    override fun answerFromApp(callId: UUID) {
        acceptLocally(callId)
        val video = session?.takeIf { it.id == callId }?.video == true
        if (telecom.tracksCall) telecom.answer(video)
    }

    override fun mediaStarted(callId: UUID, withCamera: Boolean) {
        val current = session ?: return
        if (current.id != callId) return
        current.microphone = true
        if (withCamera) current.camera = true
        if (current.style != Style.Incoming && !telecom.tracksCall) startAudio()
        val running = service
        if (running != null && promoted) bringToForeground(running) else if (foregroundRefused) tryStartForeground()
        refreshProximity()
    }

    override fun screenShareStarted(): Boolean {
        val current = session ?: return false
        val running = service
        if (running == null || !promoted) return false
        current.sharing = true
        bringToForeground(running)
        val held = (foregroundTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) != 0
        if (!held) current.sharing = false
        refreshProximity()
        return held
    }

    override fun screenShareStopped() {
        val current = session ?: return
        current.sharing = false
        // MediaProjection.stop() has just returned on this thread. Dropping the
        // mediaProjection type in the same turn makes the system kill the process:
        // it still treats the projection as active. The next turn is after that
        // call has unwound.
        main.post {
            if (session !== current || current.sharing) return@post
            val running = service
            if (running != null && promoted) bringToForeground(running)
            refreshProximity()
        }
    }

    override fun setSpeaker(on: Boolean) {
        speakerOn = on
        if (telecom.tracksCall) {
            earpiece.value = !on
            telecom.setSpeaker(on)
        } else if (audioOn) {
            earpiece.value = audio.setSpeaker(on)
        } else {
            earpiece.value = !on
        }
        publishRoutes()
        refreshProximity()
    }

    override fun selectRoute(route: CallAudioRoute) {
        val known = telecom.tracksCall && telecomRoutes.any { it.id == route.id || it == route }
        if (known) {
            speakerOn = route.type == CallAudioRouteType.Speaker
            earpiece.value = route.type == CallAudioRouteType.Earpiece
            telecomCurrent = telecomRoutes.firstOrNull { it.id == route.id } ?: route
            telecom.selectRoute(route)
            publishRoutes()
            refreshProximity()
            return
        }
        when (route.type) {
            CallAudioRouteType.Speaker -> setSpeaker(true)
            CallAudioRouteType.Earpiece -> setSpeaker(false)
            else -> Unit
        }
    }

    @Suppress("UNUSED_PARAMETER")
    override fun postMissedCall(callId: UUID, peerUserId: UUID?, peerName: String?, video: Boolean) {
        if (session?.id == callId) return
        postMissed(callId, peerUserId, peerName)
    }

    override fun clear() {
        stopAll(cause = null)
        telecom.clear()
    }

    override fun onCallScreenShown(callId: UUID) {
        val current = session ?: return
        if (current.id != callId) return
        screenShown = true
        if (foregroundRefused) tryStartForeground()
        if (current.style == Style.Incoming) postCurrent()
    }

    override fun onCallScreenHidden() {
        if (!screenShown) return
        screenShown = false
        if (session?.style == Style.Incoming) postCurrent()
    }

    /** Reposts a ringing call when the app leaves or returns, so the full-screen intent follows. */
    fun onForegroundChanged() {
        if (session?.style == Style.Incoming) postCurrent()
    }

    fun onServiceStart(host: Service, intent: Intent?, startId: Int) {
        service = host
        this.startId = startId
        val promote = intent?.getBooleanExtra(CallService.EXTRA_PROMOTE, false) == true
        if (promote) foregroundStartPending = false
        if (intent == null) {
            if (session == null || stopAfterStart) stopService()
            return
        }
        val wasForeground = promoted
        if (promote) bringToForeground(host)
        when (intent.action) {
            CallService.ACTION_DECLINE -> endFromShade(intent, CallEndCause.Rejected)
            CallService.ACTION_HANGUP -> endFromShade(intent, CallEndCause.Local)
            CallService.ACTION_SPEAKER -> callbacks.onToggleSpeaker()
        }
        if (stopAfterStart || session == null) {
            stopService()
        } else if (!promoted && !wasForeground) {
            host.stopSelf(startId)
            service = null
        }
    }

    fun onServiceDestroyed() {
        val retry = session != null && promoted
        service = null
        promoted = false
        foregroundTypes = 0
        foregroundStartPending = false
        if (retry) foregroundRefused = true
    }

    private fun endFromShade(intent: Intent, cause: CallEndCause) {
        val id = Ids.parse(intent.getStringExtra(CallIntents.EXTRA_CALL_ID)) ?: session?.id ?: return
        if (session?.id == id) reportEnded(id, cause)
        callbacks.onEnd(id)
    }

    private fun acceptLocally(callId: UUID) {
        val current = session ?: return
        if (current.id != callId || current.style != Style.Incoming) return
        ringer.stop()
        current.style = Style.Ongoing
        if (!telecom.tracksCall) startAudio()
        postCurrent()
        refreshProximity()
        if (foregroundRefused) tryStartForeground()
    }

    private fun begin(callId: UUID, peerName: String, video: Boolean, outgoing: Boolean, style: Style) {
        val existing = session
        if (existing != null && existing.id != callId) stopAll(CallEndCause.Local)
        val same = session
        if (same != null && same.id == callId) {
            same.name = shownName(peerName)
            same.video = video
            speakerOn = video
            earpiece.value = !video
            publishRoutes()
            return
        }
        session = Session(
            id = callId,
            name = shownName(peerName),
            video = video,
            outgoing = outgoing,
            style = style,
        )
        lastId = callId
        speakerOn = video
        earpiece.value = !video
        telecomRoutes = emptyList()
        telecomCurrent = null
        telecomActivated = false
        screenShown = false
        foregroundRefused = false
        foregroundTypes = 0
        publishRoutes()
    }

    private fun postCurrent() {
        val current = session ?: return
        shade.ensureChannels()
        val notification = notificationFor(current)
        val host = service
        if (promoted && host != null) {
            // Once the phoneCall service owns the notification, startForeground is the only
            // legal way to replace a CallStyle entry on Android 15+.
            bringToForeground(host)
        } else if (canNotifyCallStyle(notification)) {
            shade.post(null, SystemNotifier.ID_CALL, notification)
        }
        shade.cancel(SystemNotifier.callTag(current.id), SystemNotifier.ID_CALL)
    }

    /**
     * Android 15 (API 35) throws from [android.app.NotificationManager.notify] when a CallStyle
     * notification has no full-screen intent and is not yet the phoneCall foreground-service
     * notification. The service posts that one from [bringToForeground].
     */
    private fun canNotifyCallStyle(notification: Notification): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM || notification.fullScreenIntent != null

    private fun notificationFor(current: Session) = when (current.style) {
        Style.Incoming -> notices.incoming(current.id, current.name, current.video, fullScreen = fullScreenNow(current))
        Style.Outgoing -> notices.outgoing(current.id, current.name, current.video)
        Style.Ongoing -> notices.ongoing(current.id, current.name, current.video, current.connectedAt)
    }

    private fun fullScreenNow(current: Session): Boolean =
        current.style == Style.Incoming && !appInForeground() && !screenShown

    private fun postMissed(callId: UUID, peerUserId: UUID?, peerName: String?) {
        shade.ensureChannels()
        shade.cancel(null, SystemNotifier.ID_CALL)
        shade.post(
            SystemNotifier.callTag(callId),
            SystemNotifier.ID_CALL,
            notices.missed(callId, peerUserId, missedTitle(peerName), missedChannelId()),
        )
    }

    private fun stopAll(cause: CallEndCause?) {
        ringer.stop()
        proximity.release()
        if (audioOn) {
            audio.stop()
            audioOn = false
        }
        val ending = session
        session = null
        screenShown = false
        foregroundRefused = false
        telecomActivated = false
        speakerOn = false
        earpiece.value = true
        telecomRoutes = emptyList()
        telecomCurrent = null
        publishRoutes()
        if (ending != null && cause != null) telecom.disconnect(cause)
        cancelBoth(ending?.id ?: lastId)
        stopService()
    }

    private fun cancelBoth(callId: UUID?) {
        shade.cancel(null, SystemNotifier.ID_CALL)
        shade.cancel(SystemNotifier.callTag(callId), SystemNotifier.ID_CALL)
    }

    private fun startAudio() {
        if (telecom.tracksCall || audioOn) return
        earpiece.value = audio.start(speakerOn)
        audioOn = true
        publishRoutes()
        refreshProximity()
    }

    private fun publishRoutes() {
        if (telecom.tracksCall && telecomRoutes.isNotEmpty()) {
            routes.value = telecomRoutes
            currentRouteFlow.value = telecomCurrent ?: telecomRoutes.firstOrNull()
            return
        }
        routes.value = listOf(phoneEarpiece, phoneSpeaker)
        currentRouteFlow.value = if (earpiece.value) phoneEarpiece else phoneSpeaker
    }

    private fun refreshProximity() {
        val current = session
        val hold = current != null && current.style != Style.Incoming && earpiece.value && !current.picture
        if (hold) proximity.acquire() else proximity.release()
    }

    private fun tryStartForeground() {
        val id = session?.id ?: return
        val running = service
        if (promoted && running != null) {
            bringToForeground(running)
            return
        }
        if (foregroundStartPending) return
        val ok = ForegroundStart.tryStart(starter, CallService.promoteIntent(context, id))
        foregroundRefused = !ok
        foregroundStartPending = ok
    }

    private fun bringToForeground(host: Service) {
        val microphone = session?.microphone == true
        val sharing = session?.sharing == true
        val note = notificationOrMinimal()
        for (type in callForegroundTypeAttempts(microphone, sharing)) {
            if (dropsHeldProjection(sharing, type)) continue
            if (promote(host, note, type)) return
        }
        // The CallStyle notification itself can be what threw. A plain one keeps the call up.
        // Never trade away a projection type that is already held: the system would stop the share.
        if (sharing && (foregroundTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) != 0) return
        if (promote(host, notices.minimal(), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)) return
        if (!promoted) foregroundRefused = true
    }

    /** A later update must not remove mediaProjection while this call is still sharing. */
    private fun dropsHeldProjection(sharing: Boolean, type: Int): Boolean {
        if (!sharing) return false
        val held = (foregroundTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) != 0
        return held && (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) == 0
    }

    private fun promote(host: Service, notification: Notification, type: Int): Boolean =
        try {
            ServiceCompat.startForeground(host, SystemNotifier.ID_CALL, notification, type)
            foregroundTypes = type
            promoted = true
            foregroundRefused = false
            true
        } catch (_: Exception) {
            false
        }

    private fun notificationOrMinimal() = session?.let(::notificationFor) ?: notices.minimal()

    private fun stopService() {
        // startForegroundService without startForeground crashes the process. If the call
        // ends before onServiceStart, wait for that start, promote, then stop.
        if (foregroundStartPending && service == null) {
            stopAfterStart = true
            return
        }
        stopAfterStart = false
        val host = service
        val id = startId
        promoted = false
        foregroundTypes = 0
        service = null
        if (host != null && id != 0) host.stopSelf(id)
        context.stopService(Intent(context, CallService::class.java))
    }

    private fun shownName(raw: String): String =
        raw.trim().take(MAX_NAME).ifEmpty { CallTexts.INCOMING_CALL_PLACEHOLDER }

    private fun missedTitle(name: String?): String {
        val trimmed = name?.trim()?.take(MAX_NAME).orEmpty()
        if (trimmed.isEmpty() || trimmed == CallTexts.INCOMING_CALL_PLACEHOLDER) return SystemNotifier.APP_TITLE
        return trimmed
    }

    private fun rememberEnded(id: UUID) {
        if (id in endedIds) return
        endedIds.addLast(id)
        while (endedIds.size > ENDED_LIMIT) endedIds.removeFirst()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private enum class Style { Incoming, Outgoing, Ongoing }

    private class Session(
        val id: UUID,
        var name: String,
        var video: Boolean,
        val outgoing: Boolean,
        var style: Style,
        var connectedAt: Long? = null,
        var camera: Boolean = false,
        var sharing: Boolean = false,
        var microphone: Boolean = false,
    ) {
        val picture: Boolean get() = video || camera || sharing
    }

    private companion object {
        const val MAX_NAME = 64
        const val ENDED_LIMIT = 20
    }
}

/**
 * Foreground-service types to try, first choice first.
 *
 * Sharing tries the projection type before any set that lacks it. A combined
 * `phoneCall|microphone|mediaProjection` start can fail the microphone while-in-use check
 * while the consent screen is closing; dropping the projection type there makes the system
 * stop the capture immediately.
 */
internal fun callForegroundTypeAttempts(microphone: Boolean, sharing: Boolean): List<Int> {
    val phone = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
    val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    val projection = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    val call = if (microphone) phone or mic else phone
    val attempts = ArrayList<Int>(4)
    attempts += if (sharing) call or projection else call
    if (sharing && microphone) attempts += phone or projection
    if (call !in attempts) attempts += call
    if (phone !in attempts) attempts += phone
    return attempts
}

/** [CallService] reaches the system through this, including before the activity container is readable. */
internal object CallSystemRegistry {
    @Volatile var current: AndroidCallSystem? = null
}
