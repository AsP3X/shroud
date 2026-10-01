package de.corespace.shroud.core.notifications

import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.ShroudApi
import de.corespace.shroud.core.storage.StorageSeal
import de.corespace.shroud.core.storage.SealedFile
import de.corespace.shroud.testing.FakeAppClock
import de.corespace.shroud.testing.FakeSharedPreferences
import de.corespace.shroud.testing.XorSealer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.File

/**
 * A [NotificationsController] on fakes (notifications-push §7.2): a virtual clock, a fake shade,
 * recorded sounds and haptics, a hand-set foreground and accessibility state.
 */
class ControllerHarness(
    test: TestScope,
    namesDir: File? = null,
    api: () -> ShroudApi = { error("this test talks to no server") },
) {
    val clock = FakeAppClock()
    val seal = StorageSeal()
    val prefsFile = FakeSharedPreferences()
    val preferences = NotificationPreferences(prefsFile, seal)
    val sink = FakeSink()
    val store = FakeChannelStore()
    val channels = NotificationChannels(store) { preferences.state.value }
    val notifier = SystemNotifier(sink, channels, seal, DirectExecutor)
    val sounds = RecordingSoundPlayer()
    val accessibility = FakeAccessibility()
    val permission = FakePermission()
    val pushHooks = FakePushHooks()
    var resumed = true
    var systemAllows = true
    var deletedKeys = 0
    val nameCache: NotificationNameCache? = namesDir?.let { dir ->
        NotificationNameCache(
            file = SealedFile(File(dir, NotificationNameCache.FILE_NAME), XorSealer()),
            deleteKey = { deletedKeys++ },
            seal = seal,
            namesOn = { preferences.showSender },
            writer = DirectExecutor,
        )
    }

    /** Collectors start at once; banner timers run on the test's virtual time; cancelled with the test. */
    val scope: CoroutineScope = CoroutineScope(test.backgroundScope.coroutineContext + UnconfinedTestDispatcher(test.testScheduler))

    val controller = NotificationsController(
        preferences = preferences,
        permission = permission,
        sounds = sounds,
        systemNotifier = notifier,
        channels = channels,
        nameCache = nameCache,
        isResumed = { resumed },
        accessibility = accessibility,
        api = api,
        systemAllows = { systemAllows },
        clock = clock,
        scope = scope,
        pushHooks = { pushHooks },
        io = UnconfinedTestDispatcher(test.testScheduler),
    )

    val haptics = ArrayList<Haptic>()

    init {
        channels.ensure()
        scope.launch { controller.haptics.collect { haptics += it } }
    }
}
