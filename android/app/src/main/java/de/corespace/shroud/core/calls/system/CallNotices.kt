package de.corespace.shroud.core.calls.system

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import de.corespace.shroud.R
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.notifications.NotificationTap
import de.corespace.shroud.core.notifications.SystemNotifier
import java.util.UUID

/** Channels this package creates. Missed calls use the id from [de.corespace.shroud.core.notifications.NotificationChannels]. */
internal object CallChannels {
    const val INCOMING = "calls.incoming"
    const val ONGOING = "calls.ongoing"
}

/** The call screen every ring and answer pending intent targets. */
internal const val CALL_ACTIVITY_CLASS = "de.corespace.shroud.ui.calls.CallActivity"

/**
 * CallStyle notifications (calls §6.3). Built here so a test can read the pending intents
 * without posting. The live call is untagged [SystemNotifier.ID_CALL]; a missed call uses
 * [SystemNotifier.callTag] on that same id after the ring is cancelled.
 */
internal class CallNotices(private val context: Context) {
    fun incoming(callId: UUID, name: String, video: Boolean, fullScreen: Boolean): Notification {
        val person = person(name)
        val decline = servicePi(callId, CallService.ACTION_DECLINE, "decline")
        val answer = activityPi(callId, "answer", "answer")
        val show = activityPi(callId, "show", "show")
        val builder = base(if (fullScreen) CallChannels.INCOMING else CallChannels.ONGOING)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentTitle(name)
            .setContentText(if (video) INCOMING_VIDEO else INCOMING_VOICE)
            .setContentIntent(show)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(person, decline, answer).setIsVideo(video),
            )
        if (fullScreen) builder.setFullScreenIntent(show, true)
        return builder.build()
    }

    fun outgoing(callId: UUID, name: String, video: Boolean): Notification =
        ongoingLike(callId, name, video, whenMs = null, text = OUTGOING)

    fun ongoing(callId: UUID, name: String, video: Boolean, whenMs: Long?): Notification =
        ongoingLike(
            callId,
            name,
            video,
            whenMs,
            text = if (video) ONGOING_VIDEO else ONGOING_VOICE,
        )

    /**
     * Missed call on the shared call id. The action title is exactly "Call back" and opens the
     * existing notification tap (kind + peer, never a name).
     */
    fun missed(callId: UUID, peerUserId: UUID?, title: String, channelId: String): Notification {
        val open = tap(callId, peerUserId, "missed-open")
        val callBack = tap(callId, peerUserId, "missed-callback")
        return base(channelId)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentTitle(title)
            .setContentText(NotificationKind.MissedCall.bodyLine)
            .setContentIntent(open)
            .addAction(0, CALL_BACK, callBack)
            .build()
    }

    /** Silent ongoing notification used when a promote arrives after the call is already gone. */
    fun minimal(): Notification = base(CallChannels.ONGOING)
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setOngoing(true)
        .setSilent(true)
        .setContentTitle(SystemNotifier.APP_TITLE)
        .setContentText(ONGOING_VOICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    private fun ongoingLike(
        callId: UUID,
        name: String,
        video: Boolean,
        whenMs: Long?,
        text: String,
    ): Notification {
        val person = person(name)
        val hangup = servicePi(callId, CallService.ACTION_HANGUP, "hangup")
        val speaker = servicePi(callId, CallService.ACTION_SPEAKER, "speaker")
        val show = activityPi(callId, "show", "show")
        val builder = base(CallChannels.ONGOING)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(name)
            .setContentText(text)
            .setContentIntent(show)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangup).setIsVideo(video))
            .addAction(0, SPEAKER, speaker)
        if (whenMs != null) builder.setWhen(whenMs).setUsesChronometer(true).setShowWhen(true)
        return builder.build()
    }

    private fun base(channelId: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_shroud)
            .setColor(ACCENT)

    private fun person(name: String): Person = Person.Builder().setName(name).setImportant(true).build()

    private fun activityPi(callId: UUID, action: String, slot: String): PendingIntent {
        val intent = Intent()
            .setClassName(context, CALL_ACTIVITY_CLASS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(CallIntents.EXTRA_CALL_ID, Ids.wire(callId))
            .putExtra(CallIntents.EXTRA_ACTION, action)
        return PendingIntent.getActivity(context, requestCode(callId, slot), intent, PI_FLAGS)
    }

    private fun servicePi(callId: UUID, action: String, slot: String): PendingIntent {
        val intent = Intent(context, CallService::class.java)
            .setAction(action)
            .putExtra(CallIntents.EXTRA_CALL_ID, Ids.wire(callId))
        return PendingIntent.getService(context, requestCode(callId, slot), intent, PI_FLAGS)
    }

    private fun tap(callId: UUID, peerUserId: UUID?, slot: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode(callId, slot),
            NotificationTap.intent(context, NotificationKind.MissedCall, peerUserId),
            PI_FLAGS,
        )

    private fun requestCode(callId: UUID, slot: String): Int =
        (Ids.wire(callId) + ":" + slot).hashCode() and 0x7fffffff

    private companion object {
        const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        const val RING_TIMEOUT_MS = 70_000L
        const val ACCENT = 0xFF5E5CE6.toInt()
        const val INCOMING_VOICE = "Incoming voice call"
        const val INCOMING_VIDEO = "Incoming video call"
        const val ONGOING_VOICE = "Ongoing voice call"
        const val ONGOING_VIDEO = "Ongoing video call"
        const val OUTGOING = "Calling…"
        const val SPEAKER = "Speaker"
        const val CALL_BACK = "Call back"
    }
}
