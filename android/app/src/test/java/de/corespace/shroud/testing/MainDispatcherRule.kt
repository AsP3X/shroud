package de.corespace.shroud.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Replaces `Dispatchers.Main` for one test: controllers live on `Dispatchers.Main.immediate`
 * (00-plan §1.1 rule 3), which has no looper on the JVM.
 *
 * ```
 * @get:Rule val main = MainDispatcherRule()
 * @Test fun x() = runTest(main.dispatcher) { … }
 * ```
 *
 * The default [UnconfinedTestDispatcher] runs launched work eagerly, like `Main.immediate` on the
 * main thread; pass a `StandardTestDispatcher()` to step through queued work by hand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
