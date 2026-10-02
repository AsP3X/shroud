package de.corespace.shroud.core.calls.system

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import de.corespace.shroud.core.calls.CallEndCause
import de.corespace.shroud.core.model.Ids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Self-managed Telecom via core-telecom 1.0. Registration or `addCall` failing leaves the call
 * in-app ([CallTelecomListener.onUnavailable]); it does not end it. Endpoint names are not logged.
 */
internal class CoreCallTelecom(
    private val context: Context,
    private val scope: CoroutineScope,
) : CallTelecom {
    override var listener: CallTelecomListener = object : CallTelecomListener {}

    private var manager: CallsManager? = null
    private var available = false
    private val callId = AtomicReference<UUID?>(null)
    private val control = AtomicReference<CallControlScope?>(null)
    private val ended = AtomicBoolean(false)
    private val localEnd = AtomicBoolean(false)
    private val endpoints = AtomicReference<List<CallEndpointCompat>>(emptyList())

    @Volatile private var wantedSpeaker = false
    @Volatile private var remembered: CallEndpointCompat? = null
    @Volatile private var currentType = CallEndpointCompat.TYPE_EARPIECE

    override val tracksCall: Boolean
        get() = available && callId.get() != null

    override fun register() {
        if (available) return
        try {
            val calls = CallsManager(context)
            calls.registerAppWithTelecom(
                CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING,
            )
            manager = calls
            available = true
        } catch (_: Exception) {
            manager = null
            available = false
        }
    }

    override fun add(callId: UUID, name: String, video: Boolean, outgoing: Boolean) {
        if (!available) return
        val calls = manager ?: return
        this.callId.set(callId)
        ended.set(false)
        localEnd.set(false)
        control.set(null)
        val attributes = CallAttributesCompat(
            name,
            Uri.parse("shroud:call/${Ids.wire(callId)}"),
            if (outgoing) CallAttributesCompat.DIRECTION_OUTGOING else CallAttributesCompat.DIRECTION_INCOMING,
            if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            0,
        )
        scope.launch {
            val entered = AtomicBoolean(false)
            try {
                calls.addCall(
                    callAttributes = attributes,
                    onAnswer = { listener.onAnswer(callId) },
                    onDisconnect = { cause ->
                        if (ended.compareAndSet(false, true)) listener.onEnded(callId, cause.toEndCause())
                    },
                    onSetActive = {},
                    onSetInactive = {
                        try {
                            control.get()?.setActive()
                        } catch (_: Exception) {
                        }
                    },
                ) {
                    entered.set(true)
                    control.set(this)
                    launch {
                        currentCallEndpoint.collect { endpoint ->
                            currentType = endpoint.type
                            if (endpoint.type != CallEndpointCompat.TYPE_SPEAKER) remembered = endpoint
                            listener.onEarpiece(callId, endpoint.type == CallEndpointCompat.TYPE_EARPIECE)
                            applySpeaker()
                        }
                    }
                    launch {
                        availableEndpoints.collect { list ->
                            endpoints.set(list)
                            applySpeaker()
                        }
                    }
                    launch {
                        var first = true
                        isMuted.collect { muted ->
                            if (first) first = false else listener.onMute(callId, muted)
                        }
                    }
                    launch { awaitCancellation() }
                }
                if (entered.get() && ended.compareAndSet(false, true)) {
                    listener.onEnded(callId, CallEndCause.Remote)
                }
            } catch (_: CancellationException) {
                finishAdd(callId, entered.get(), CallEndCause.Remote)
            } catch (_: Exception) {
                finishAdd(callId, entered.get(), CallEndCause.Error)
            }
        }
    }

    override fun answer(video: Boolean) {
        val ctrl = control.get() ?: return
        val type = if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL
        scope.launch { quietly { ctrl.answer(type) } }
    }

    override fun setActive() {
        val ctrl = control.get() ?: return
        scope.launch { quietly { ctrl.setActive() } }
    }

    override fun setSpeaker(on: Boolean) {
        wantedSpeaker = on
        val ctrl = control.get() ?: return
        scope.launch { quietly { ctrl.applySpeaker() } }
    }

    override fun disconnect(cause: CallEndCause) {
        if (!ended.compareAndSet(false, true)) return
        localEnd.set(true)
        val ctrl = control.getAndSet(null)
        callId.set(null)
        scope.launch { quietly { ctrl?.disconnect(DisconnectCause(cause.toDisconnect())) } }
    }

    override fun clear() {
        ended.set(true)
        localEnd.set(true)
        val ctrl = control.getAndSet(null)
        callId.set(null)
        scope.launch { quietly { ctrl?.disconnect(DisconnectCause(DisconnectCause.LOCAL)) } }
    }

    private fun finishAdd(id: UUID, entered: Boolean, cause: CallEndCause) {
        if (!entered) {
            if (callId.compareAndSet(id, null)) listener.onUnavailable(id)
        } else if (ended.compareAndSet(false, true)) {
            listener.onEnded(id, if (localEnd.get()) CallEndCause.Local else cause)
        }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    private suspend fun CallControlScope.applySpeaker() {
        val target = pick(wantedSpeaker) ?: return
        val wantType = if (wantedSpeaker) CallEndpointCompat.TYPE_SPEAKER else target.type
        if (currentType == wantType) return
        try {
            requestEndpointChange(target)
        } catch (_: Exception) {
        }
    }

    private fun pick(speaker: Boolean): CallEndpointCompat? {
        val all = endpoints.get()
        if (speaker) return all.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
        remembered?.takeIf { it.type != CallEndpointCompat.TYPE_SPEAKER }?.let { return it }
        return all.firstOrNull { it.type == CallEndpointCompat.TYPE_BLUETOOTH }
            ?: all.firstOrNull { it.type == CallEndpointCompat.TYPE_WIRED_HEADSET }
            ?: all.firstOrNull { it.type == CallEndpointCompat.TYPE_EARPIECE }
    }

    private fun CallEndCause.toDisconnect(): Int = when (this) {
        CallEndCause.Local -> DisconnectCause.LOCAL
        CallEndCause.Remote -> DisconnectCause.REMOTE
        CallEndCause.Rejected -> DisconnectCause.REJECTED
        CallEndCause.Missed -> DisconnectCause.MISSED
        CallEndCause.AnsweredElsewhere -> DisconnectCause.ANSWERED_ELSEWHERE
        CallEndCause.Error -> DisconnectCause.ERROR
    }

    private fun DisconnectCause.toEndCause(): CallEndCause = when (code) {
        DisconnectCause.LOCAL -> CallEndCause.Local
        DisconnectCause.REMOTE, DisconnectCause.CANCELED -> CallEndCause.Remote
        DisconnectCause.REJECTED -> CallEndCause.Rejected
        DisconnectCause.MISSED -> CallEndCause.Missed
        DisconnectCause.ANSWERED_ELSEWHERE -> CallEndCause.AnsweredElsewhere
        else -> CallEndCause.Error
    }
}
