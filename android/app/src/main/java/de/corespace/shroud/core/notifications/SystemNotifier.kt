package de.corespace.shroud.core.notifications

import androidx.core.app.NotificationCompat
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.storage.StorageSeal
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * One system notification as [SystemNotifier] decided it; a [NotificationSink] renders and posts it.
 *
 * @property kind what a tap opens (null: the app only, e.g. the signed-out notice).
 * @property name the shown sender name; non-null also draws the avatar as the large icon. Never
 *   in the tap intent (notifications-push §5.7.5).
 * @property count how many arrivals this notification stands for (kept in its extras, N6).
 * @property number the unread total for launchers that show numbers (`setNumber`, §5.7.3).
 * @property timeoutMs the system removes the notification after this long (a ring's 60 s).
 */
data class PostSpec(
    val tag: String,
    val id: Int,
    val channelId: String,
    val kind: NotificationKind?,
    val peerUserId: UUID?,
    val title: String,
    val name: String?,
    val body: String,
    val category: String,
    val count: Int = 1,
    val number: Int = 0,
    val timeoutMs: Long? = null,
)

/** The platform's notification manager, behind a seam so the posting rules run on the JVM. */
interface NotificationSink {
    /** `NotificationManagerCompat.areNotificationsEnabled()`: the app may post at all. */
    fun areEnabled(): Boolean

    /** Renders and posts [spec] under its tag and id (replacing one already there). */
    fun post(spec: PostSpec)

    fun cancel(tag: String, id: Int)

    fun cancelAll()

    /** The arrival count stored in the notification showing under [tag]/[id], or null when none shows. */
    fun activeCount(tag: String, id: Int): Int?

    /** Tag and id of every notification of the app showing now. */
    fun activeKeys(): List<Pair<String, Int>>
}

/**
 * Builds, posts and cancels the app's system notifications (notifications-push §5.7; iOS
 * `NotificationPayload.dress`, `ios/ShroudShared/NotificationPayload.swift:106-123`, and
 * `NotificationsController.postLocal`, `NotificationsController.swift:162-176`).
 *
 * Never message text: the body is the kind's line or the count line, the title the sender's name
 * (only when the device shows names) or "Shroud" (§2: iOS leaves the title empty and the system
 * shows the app name; Android and the web write "Shroud", `sw.js:82`). Text appears only in the
 * in-app banner (`NotificationsController.swift:26-29`).
 *
 * One notification per chat that counts (N6, P11c; web `sw.js:71-98`): a chat's messages share
 * tag = conversation id and id [ID_MESSAGE]; a new one replaces it with "2 new messages", "3 new
 * messages", … — the count lives in the posted notification's own extras, so nothing of ours must
 * survive a process death. Reactions keep their own tag (`<conversation>:reaction`, [ID_REACTION])
 * and never count. Calls share the slot `call:<call id>`/[ID_CALL], so a missed call replaces its ring.
 *
 * Every operation runs in order on [executor] (one thread): counting reads the shade, then posts,
 * and two arrivals must not both read "none showing". While [seal] is set (a wipe is running)
 * nothing new is posted; cancelling still works.
 */
class SystemNotifier(
    private val sink: NotificationSink,
    private val channels: NotificationChannels,
    private val seal: StorageSeal,
    private val executor: Executor = Executors.newSingleThreadExecutor { Thread(it, "shroud-notifications").apply { isDaemon = true } },
) {
    /**
     * A push (UnifiedPush, or an event on the background connection) for the process to show
     * (notifications-push §5.7.2; W3-PUSH's dispatcher calls it after the call kinds went to Calls
     * and `NotificationsController.onPushWhileRunning` declined). [name] is the payload's `sender`
     * or, on the socket path, the [NotificationNameCache] entry. A `read` push closes the chat's
     * notifications instead (P11d). Unknown kinds are dropped (a Web Push has no fallback line).
     */
    fun post(contents: PushContents, name: String?) {
        if (contents.isRead) {
            contents.conversationId?.let(::cancelChat)
            return
        }
        val kind = contents.kind ?: return
        val number = contents.badge ?: 0
        when (kind) {
            NotificationKind.CallEnded -> cancel(callTag(contents.callId), ID_CALL)
            NotificationKind.Call, NotificationKind.VideoCall, NotificationKind.MissedCall ->
                enqueuePost { callSpec(kind, contents.callId, contents.peerUserId, name, number) }
            else -> enqueuePost { spec(kind, name, contents.peerUserId, contents.conversationId, number) }
        }
    }

    /**
     * A notification from the app itself: it was in the background with its socket still open
     * (`postLocal`, `NotificationsController.swift:162-176`; §5.12.10). Worded like a push — [name]
     * already filtered by Show Sender, never the message — counted the same way, with the local
     * unread total as [badge].
     */
    fun postLocal(kind: NotificationKind, name: String?, peerUserId: UUID?, conversationId: UUID?, badge: Int) {
        enqueuePost { spec(kind, name, peerUserId, conversationId, badge) }
    }

    /**
     * Closes a chat's notifications once it was read here or elsewhere: its message notification
     * and its reactions' (`clearDelivered`, `NotificationsController.swift:227-241`; §5.7.4).
     */
    fun cancelChat(conversationId: UUID) {
        val thread = Ids.wire(conversationId)
        cancel(thread, ID_MESSAGE)
        cancel(reactionTag(thread), ID_REACTION)
    }

    /** A ring's notification goes (the call ended, was answered or declined elsewhere). */
    fun cancelCall(callId: UUID) = cancel(callTag(callId), ID_CALL)

    /**
     * Every contact-request notification goes: the requests list is on screen (web-parity §7.6;
     * web `AppShell.tsx:813-816`).
     */
    fun cancelContactRequests() {
        executor.execute {
            runCatching {
                for ((tag, id) in sink.activeKeys()) {
                    if (id == ID_CONTACT_REQUEST && (tag == TAG_CONTACTS || tag.startsWith("$TAG_CONTACTS:"))) sink.cancel(tag, id)
                }
            }
        }
    }

    /**
     * The first chat list after an unlock settles what was read elsewhere meanwhile (web-parity §7.6;
     * web `AppShell.tsx:802-812`): the message notifications of [readChats] (unread count 0) and the
     * reaction notifications of [reactionsSeenChats] (no unseen reactions) go.
     */
    fun closeSettledChats(readChats: Collection<UUID>, reactionsSeenChats: Collection<UUID>) {
        for (chat in readChats) cancel(Ids.wire(chat), ID_MESSAGE)
        for (chat in reactionsSeenChats) cancel(reactionTag(Ids.wire(chat)), ID_REACTION)
    }

    /** Every notification of the app (Log Out / removal wipe; `DeviceDataWipe.swift:176-179`). */
    fun cancelAll() {
        executor.execute { runCatching { sink.cancelAll() } }
    }

    /**
     * The one neutral notice after a removal wipe started by a push (web-parity §5.3; web `sw.js:129-135`):
     * "Shroud" / "This phone was signed out." — earlier notifications may have named a contact, so
     * they are cancelled first. Posted even while the wipe's seal is still set.
     *
     * @param deviceNoun "phone" or "tablet" (`DeviceNoun.current`, W2-AUTH-WIPE).
     */
    fun postSignedOutNotice(deviceNoun: String = "phone") {
        executor.execute {
            runCatching { sink.cancelAll() }
            // The wipe reset the preferences: the default channels may not exist yet.
            runCatching { channels.ensure() }
            postNow(
                PostSpec(
                    tag = TAG_ACCOUNT,
                    id = ID_SIGNED_OUT,
                    channelId = channels.messages(),
                    kind = null,
                    peerUserId = null,
                    title = APP_TITLE,
                    name = null,
                    body = "This $deviceNoun was signed out.",
                    category = NotificationCompat.CATEGORY_STATUS,
                ),
            )
        }
    }

    /** Waits for every operation queued so far (tests; the wipe before it checks the shade). */
    fun drain() {
        val done = java.util.concurrent.CountDownLatch(1)
        executor.execute { done.countDown() }
        done.await()
    }

    private fun cancel(tag: String, id: Int) {
        executor.execute { runCatching { sink.cancel(tag, id) } }
    }

    private fun enqueuePost(make: () -> PostSpec) {
        executor.execute {
            if (seal.isSealed) return@execute
            postNow(make())
        }
    }

    /** On the executor. Posting without the permission throws on 13+ (§5.7.1): dropped. */
    private fun postNow(spec: PostSpec) {
        if (!sink.areEnabled()) return
        runCatching { sink.post(spec) }
    }

    /** Message, reaction, contact request and test (§5.7.2). On the executor: it reads the shade. */
    private fun spec(kind: NotificationKind, name: String?, peerUserId: UUID?, conversationId: UUID?, number: Int): PostSpec {
        val thread = PushContents.threadFor(kind, conversationId)
        return when (kind) {
            NotificationKind.Message -> {
                val count = (sink.activeCount(thread, ID_MESSAGE) ?: 0) + 1
                PostSpec(
                    tag = thread, id = ID_MESSAGE, channelId = channels.messages(), kind = kind, peerUserId = peerUserId,
                    title = name ?: APP_TITLE, name = name, body = messageBody(count),
                    category = NotificationCompat.CATEGORY_MESSAGE, count = count, number = number,
                )
            }
            NotificationKind.Reaction -> PostSpec(
                tag = reactionTag(thread), id = ID_REACTION, channelId = channels.messages(), kind = kind, peerUserId = peerUserId,
                title = name ?: APP_TITLE, name = name, body = kind.bodyLine,
                category = NotificationCompat.CATEGORY_SOCIAL, number = number,
            )
            NotificationKind.ContactRequest -> PostSpec(
                tag = peerUserId?.let { "$TAG_CONTACTS:${Ids.wire(it)}" } ?: TAG_CONTACTS, id = ID_CONTACT_REQUEST,
                channelId = channels.contactRequests(), kind = kind, peerUserId = peerUserId,
                title = name ?: APP_TITLE, name = name, body = kind.bodyLine,
                category = NotificationCompat.CATEGORY_SOCIAL, number = number,
            )
            NotificationKind.Test -> PostSpec(
                tag = "test", id = ID_TEST, channelId = channels.messages(), kind = kind, peerUserId = null,
                title = APP_TITLE, name = null, body = kind.bodyLine, category = NotificationCompat.CATEGORY_STATUS,
            )
            NotificationKind.Call, NotificationKind.VideoCall, NotificationKind.MissedCall, NotificationKind.CallEnded ->
                callSpec(kind, null, peerUserId, name, number)
        }
    }

    /**
     * Call kinds until W3-CALLS-SYSTEM rings itself (notifications-push §5.18): a ring is a plain
     * "Incoming call" / "Incoming video call" on the missed-calls channel for the ring time (60 s);
     * a missed call replaces it in the same slot. A tap only brings the app forward.
     */
    private fun callSpec(kind: NotificationKind, callId: UUID?, peerUserId: UUID?, name: String?, number: Int): PostSpec {
        val ring = kind == NotificationKind.Call || kind == NotificationKind.VideoCall
        return PostSpec(
            tag = callTag(callId), id = ID_CALL, channelId = channels.missedCalls(), kind = kind, peerUserId = peerUserId,
            title = name ?: APP_TITLE, name = name, body = kind.bodyLine,
            category = if (ring) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MISSED_CALL,
            number = number, timeoutMs = if (ring) RING_TIMEOUT_MS else null,
        )
    }

    companion object {
        const val ID_MESSAGE = 1
        const val ID_REACTION = 2
        const val ID_CONTACT_REQUEST = 3

        /** Rings and missed calls share it: a missed call replaces its ring (W3-CALLS-SYSTEM posts there too). */
        const val ID_CALL = 4
        const val ID_TEST = 5
        const val ID_SIGNED_OUT = 7

        const val TAG_ACCOUNT = "account"

        /** Contact requests: `contacts:<requester id>` (one per requester, §5.7.2). */
        const val TAG_CONTACTS = "contacts"

        /** The title when no name is shown (§2; the design's *Message, sender hidden*). */
        const val APP_TITLE = "Shroud"

        /** The ring time (`TTL: 60` of a ring push, P2). */
        const val RING_TIMEOUT_MS = 60_000L

        /** Where a posted notification keeps the arrivals it counts (N6; web `data.count`). */
        const val EXTRA_COUNT = "shroud.count"

        /** "New message", then "2 new messages", … (web `sw.js:83`, notifications-push N6). */
        fun messageBody(count: Int): String = if (count > 1) "$count new messages" else NotificationKind.Message.bodyLine

        fun reactionTag(thread: String): String = "$thread:reaction"

        fun callTag(callId: UUID?): String = callId?.let { "call:${Ids.wire(it)}" } ?: "calls"
    }
}
