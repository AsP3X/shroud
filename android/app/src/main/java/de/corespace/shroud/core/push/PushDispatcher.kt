package de.corespace.shroud.core.push

import de.corespace.shroud.core.calls.CallPush
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.CallDto
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.core.net.ContentType
import de.corespace.shroud.core.net.MessageDto
import de.corespace.shroud.core.net.wire.LenientJson
import de.corespace.shroud.core.notifications.NotificationKind
import de.corespace.shroud.core.notifications.PushContents
import de.corespace.shroud.core.realtime.RealtimeEvent
import java.util.UUID

/**
 * One arrival, from a decrypted UnifiedPush body or from the background socket while chats are
 * locked (plan §1.4, §1.7.10). Call kinds go to [calls]; `read` closes the chat; `device_removed`
 * schedules the removal worker; everything else is posted unless the app is already showing it.
 * The same id inside [PushDedup]'s window is dropped. Names on the socket path come from [nameFor].
 */
class PushDispatcher(
    private val dedup: PushDedup,
    private val clock: AppClock,
    private val post: (PushContents, String?) -> Unit,
    private val cancelChat: (UUID) -> Unit,
    private val onPushWhileRunning: (PushContents, String?) -> Boolean,
    private val calls: (CallPush) -> Unit,
    private val scheduleRemoval: () -> Unit,
    private val nameFor: (UUID) -> String?,
    private val selfUserId: () -> String?,
) {
    /** Decrypted Web Push JSON. Null plaintext is ignored. */
    fun dispatchPlaintext(bytes: ByteArray) {
        try {
            val json = LenientJson.parseObject(bytes) ?: return
            if (PushContents.isDeviceRemoval(json)) {
                if (dedup.accept(KEY_REMOVAL, clock.elapsedMillis())) runCatching { scheduleRemoval() }
                return
            }
            val contents = PushContents.fromWebPushJson(json) ?: return
            deliver(contents, contents.plainName, CallPush.Source.UnifiedPush)
        } catch (_: Exception) {
        }
    }

    /** A socket event while the vault is locked. The caller decides that; this does not check presence. */
    fun onSocket(event: RealtimeEvent) {
        try {
            when (event) {
                is RealtimeEvent.MessageNew -> onMessage(event.message)
                is RealtimeEvent.MessageReaction -> onReaction(event)
                is RealtimeEvent.ConversationRead -> onRead(event.conversationId)
                is RealtimeEvent.CallRing -> onCall(event.call, ended = false)
                is RealtimeEvent.CallEnded -> onCall(event.call, ended = true)
                is RealtimeEvent.ContactChanged -> onContact(event)
                else -> Unit
            }
        } catch (_: Exception) {
        }
    }

    private fun onMessage(message: MessageDto?) {
        if (message == null || message.contentType == ContentType.ANNOTATION) return
        if (isSelf(message.senderUserId)) {
            onRead(message.conversationId)
            return
        }
        val kind = NotificationKind.Message
        deliver(
            PushContents(
                kind = kind,
                rawKind = kind.wire,
                thread = PushContents.threadFor(kind, message.conversationId),
                conversationId = message.conversationId,
                peerUserId = message.senderUserId,
                messageId = message.id,
            ),
            nameFor(message.senderUserId),
            CallPush.Source.BackgroundSocket,
        )
    }

    private fun onReaction(event: RealtimeEvent.MessageReaction) {
        if (!event.added) return
        val kind = NotificationKind.Reaction
        val peer = event.reaction.userId
        deliver(
            PushContents(
                kind = kind,
                rawKind = kind.wire,
                thread = PushContents.threadFor(kind, event.conversationId),
                conversationId = event.conversationId,
                peerUserId = peer,
                messageId = event.reaction.messageId,
            ),
            nameFor(peer),
            CallPush.Source.BackgroundSocket,
        )
    }

    private fun onRead(conversationId: UUID?) {
        deliver(
            PushContents(
                kind = null,
                rawKind = PushContents.KIND_READ,
                thread = conversationId?.let(Ids::wire) ?: "shroud",
                conversationId = conversationId,
            ),
            null,
            CallPush.Source.BackgroundSocket,
        )
    }

    private fun onCall(call: CallDto, ended: Boolean) {
        val kind = when {
            ended -> NotificationKind.CallEnded
            call.callModality == CallModality.Video -> NotificationKind.VideoCall
            else -> NotificationKind.Call
        }
        val name = PushContents.clampName(call.callerUsername) ?: nameFor(call.callerUserId)
        deliver(
            PushContents(
                kind = kind,
                rawKind = kind.wire,
                thread = PushContents.threadFor(kind, null),
                peerUserId = call.callerUserId,
                callId = call.id,
            ),
            name,
            CallPush.Source.BackgroundSocket,
        )
    }

    private fun onContact(event: RealtimeEvent.ContactChanged) {
        if (event.kind != RealtimeEvent.ContactChanged.Kind.Request) return
        val request = event.request ?: return
        val kind = NotificationKind.ContactRequest
        val name = PushContents.clampName(request.user?.username) ?: nameFor(request.fromUserId)
        deliver(
            PushContents(
                kind = kind,
                rawKind = kind.wire,
                thread = PushContents.threadFor(kind, null),
                peerUserId = request.fromUserId,
            ),
            name,
            CallPush.Source.BackgroundSocket,
        )
    }

    private fun deliver(contents: PushContents, name: String?, source: CallPush.Source) {
        if (!dedup.accept(key(contents), clock.elapsedMillis())) return
        val kind = contents.kind
        if (kind != null && kind.isCall) {
            val id = contents.callId ?: return
            calls(CallPush(kind, id, contents.peerUserId, name, source))
            return
        }
        if (contents.isRead) {
            contents.conversationId?.let(cancelChat)
            return
        }
        if (kind == null) return
        if (onPushWhileRunning(contents, name)) return
        post(contents, name)
    }

    private fun key(contents: PushContents): String = when {
        contents.callId != null -> "${contents.rawKind}:${contents.callId}"
        contents.messageId != null -> "${contents.rawKind}:${contents.messageId}"
        contents.isRead -> "read:${contents.conversationId}"
        else -> "${contents.rawKind}:${contents.thread}:${contents.peerUserId}"
    }

    private fun isSelf(userId: UUID): Boolean = selfUserId()?.equals(userId.toString(), ignoreCase = true) == true

    companion object {
        private const val KEY_REMOVAL = "device_removed"
    }
}
