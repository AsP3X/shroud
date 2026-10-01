package de.corespace.shroud.core.notifications

import android.app.NotificationManager
import java.util.concurrent.Executor

/** Runs every task at once on the calling thread: the notifier's order without a thread. */
object DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

/** An in-memory [ChannelStore]: the system's channel list as a map of id → spec (importance editable). */
class FakeChannelStore : ChannelStore {
    val channels = LinkedHashMap<String, ChannelSpec>()
    val created = ArrayList<String>()
    val deleted = ArrayList<String>()

    override fun channelIds(): List<String> = channels.keys.toList()

    override fun importance(id: String): Int? = channels[id]?.importance

    override fun create(spec: ChannelSpec) {
        created += spec.id
        // Like the platform: an existing channel keeps the user's settings.
        if (spec.id !in channels) channels[spec.id] = spec
    }

    override fun delete(id: String) {
        deleted += id
        channels.remove(id)
    }

    /** The user changes a channel's importance in Android Settings. */
    fun userSets(id: String, importance: Int) {
        channels[id] = checkNotNull(channels[id]).copy(importance = importance)
    }

    fun blockMessages(id: String) = userSets(id, NotificationManager.IMPORTANCE_NONE)
}

/** An in-memory [NotificationSink]: the shade as a map of (tag, id) → the last spec posted there. */
class FakeSink(var enabled: Boolean = true) : NotificationSink {
    val shade = LinkedHashMap<Pair<String, Int>, PostSpec>()
    val posted = ArrayList<PostSpec>()
    var cancelAllCount = 0

    override fun areEnabled(): Boolean = enabled

    override fun post(spec: PostSpec) {
        posted += spec
        shade[spec.tag to spec.id] = spec
    }

    override fun cancel(tag: String, id: Int) {
        shade.remove(tag to id)
    }

    override fun cancelAll() {
        cancelAllCount++
        shade.clear()
    }

    override fun activeCount(tag: String, id: Int): Int? = shade[tag to id]?.count

    fun showing(tag: String, id: Int): PostSpec? = shade[tag to id]
}

/** A [SoundPlayer] that records what played. */
class RecordingSoundPlayer : SoundPlayer {
    val played = ArrayList<NotificationSound>()

    override fun play(sound: NotificationSound) {
        played += sound
    }
}

/** An [AccessibilityState] a test sets by hand. */
class FakeAccessibility(var touchExploration: Boolean = false, var recommended: (Long) -> Long = { it }) : AccessibilityState {
    override val isTouchExplorationEnabled: Boolean get() = touchExploration

    override fun recommendedTimeoutMillis(originalMillis: Long): Long = recommended(originalMillis)
}

/** A [NotificationsController.Permission] a test sets by hand. */
class FakePermission(var authorization: NotificationAuthorization = NotificationAuthorization.Authorized) : NotificationsController.Permission {
    override var wasRequested: Boolean = false

    override fun current(): NotificationAuthorization = authorization

    override fun markRequested() {
        wasRequested = true
    }
}

/** [PushDeliveryHooks] that record re-registrations. */
class FakePushHooks(var reason: String? = null) : PushDeliveryHooks {
    var registered = 0

    override fun register() {
        registered++
    }

    override fun noDeliveryReason(): String? = reason
}
