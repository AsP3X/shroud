package de.corespace.shroud.core.update

import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ClientVersionDto
import de.corespace.shroud.testing.FakeAppClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update check's decisions: the status mapping (unknown → current), the answer's parsing with
 * nulls, the 10-minute foreground limit, "Later" for one version and one process, failures that
 * keep the last answer, and a server switch that forgets the old server.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClientUpdateCheckerTest {
    /** The container's API JSON configuration. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    private val clock = FakeAppClock()

    /** Answers in order; an exception is thrown as the request's failure. A gate holds the answer back. */
    private class FakeServer {
        val answers = ArrayDeque<Any>()
        val versions = ArrayList<String>()
        var gate: CompletableDeferred<Unit>? = null

        fun queue(vararg next: Any) {
            answers.addAll(next)
        }

        suspend fun fetch(version: String): ClientVersionDto {
            versions += version
            val answer = answers.removeFirst()
            gate?.await()
            if (answer is Exception) throw answer
            return answer as ClientVersionDto
        }
    }

    private val server = FakeServer()

    private fun checker(scope: CoroutineScope) = ClientUpdateChecker("0.1.0", server::fetch, clock, scope)

    private fun available(latest: String? = "0.2.0", url: String? = "https://example.org/shroud") =
        ClientVersionDto("update_available", latest, url)

    private fun required(latest: String? = "0.3.0", url: String? = "https://example.org/shroud") =
        ClientVersionDto("update_required", latest, url)

    private val current = ClientVersionDto("current", "0.1.0", null)

    /** The offer the dialog would show now. */
    private fun ClientUpdateChecker.shownOffer(): UpdatePrompt.Available = prompt.value as UpdatePrompt.Available

    // ---- Mapping and parsing ----

    @Test
    fun statusesMapAndUnknownOnesReadAsCurrent() {
        assertEquals(ClientUpdateStatus.Current, ClientUpdateStatus.fromWire("current"))
        assertEquals(ClientUpdateStatus.UpdateAvailable, ClientUpdateStatus.fromWire("update_available"))
        assertEquals(ClientUpdateStatus.UpdateRequired, ClientUpdateStatus.fromWire("update_required"))
        assertEquals(ClientUpdateStatus.Current, ClientUpdateStatus.fromWire("update_recommended"))
        assertEquals(ClientUpdateStatus.Current, ClientUpdateStatus.fromWire("UPDATE_REQUIRED"))
        assertEquals(ClientUpdateStatus.Current, ClientUpdateStatus.fromWire(""))
    }

    @Test
    fun theAnswerDecodesWithNullsMissingKeysAndExtraKeys() {
        val full = json.decodeFromString(
            ClientVersionDto.serializer(),
            """{"status":"update_required","latest_version":"1.4.0","update_url":"https://f-droid.org/packages/de.corespace.shroud/"}""",
        )
        assertEquals(ClientVersionDto("update_required", "1.4.0", "https://f-droid.org/packages/de.corespace.shroud/"), full)
        val nulls = json.decodeFromString(ClientVersionDto.serializer(), """{"status":"update_available","latest_version":null,"update_url":null}""")
        assertEquals(ClientVersionDto("update_available", null, null), nulls)
        val missing = json.decodeFromString(ClientVersionDto.serializer(), """{"status":"current","minimum_version":"0.1.0"}""")
        assertEquals(ClientVersionDto("current", null, null), missing)
        val unknown = json.decodeFromString(ClientVersionDto.serializer(), """{"status":"beta_available","latest_version":"9.9.9"}""")
        assertEquals(ClientUpdate.CURRENT, ClientUpdate.from(unknown))
    }

    @Test
    fun anAnswerKeepsItsVersionAndOnlyAWebLink() {
        assertEquals(
            ClientUpdate(ClientUpdateStatus.UpdateAvailable, "0.2.0", "https://example.org/shroud"),
            ClientUpdate.from(available()),
        )
        // Current drops what came with it; blanks are no version.
        assertEquals(ClientUpdate.CURRENT, ClientUpdate.from(current))
        assertNull(ClientUpdate.from(available(latest = "  ")).latestVersion)
        assertEquals("1.0.0", ClientUpdate.from(available(latest = " 1.0.0 ")).latestVersion)
        assertEquals("https://example.org/a", ClientUpdate.updateLink("  https://example.org/a "))
        assertEquals("http://192.168.1.10:8080/apk", ClientUpdate.updateLink("http://192.168.1.10:8080/apk"))
        assertNull(ClientUpdate.updateLink(null))
        assertNull(ClientUpdate.updateLink(""))
        assertNull(ClientUpdate.updateLink("javascript:alert(1)"))
        assertNull(ClientUpdate.updateLink("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(ClientUpdate.updateLink("market://details?id=de.corespace.shroud"))
        assertNull(ClientUpdate.updateLink("not a url"))
    }

    @Test
    fun promptsFollowTheStatus() {
        assertEquals(UpdatePrompt.None, UpdatePrompt.of(ClientUpdate.CURRENT, "0.1.0", emptySet()))
        assertEquals(
            UpdatePrompt.Available("0.1.0", "0.2.0", null),
            UpdatePrompt.of(ClientUpdate(ClientUpdateStatus.UpdateAvailable, "0.2.0", null), "0.1.0", emptySet()),
        )
        assertEquals(UpdatePrompt.None, UpdatePrompt.of(ClientUpdate(ClientUpdateStatus.UpdateAvailable, "0.2.0", null), "0.1.0", setOf("0.2.0")))
        // "Later" never silences a required update.
        assertEquals(
            UpdatePrompt.Required("0.1.0", "0.2.0", null),
            UpdatePrompt.of(ClientUpdate(ClientUpdateStatus.UpdateRequired, "0.2.0", null), "0.1.0", setOf("0.2.0")),
        )
    }

    // ---- When it asks ----

    @Test
    fun foregroundChecksAreTenMinutesApart() = runTest(UnconfinedTestDispatcher()) {
        val checker = checker(this)
        server.queue(current, current, current)
        checker.onForeground()
        assertEquals(listOf("0.1.0"), server.versions)
        clock.advanceBy(ClientUpdateChecker.FOREGROUND_INTERVAL_MS - 1)
        checker.onForeground()
        assertEquals(1, server.versions.size)
        clock.advanceBy(1)
        checker.onForeground()
        assertEquals(2, server.versions.size)
    }

    @Test
    fun onlyAnAnswerStartsTheTenMinutes() = runTest(UnconfinedTestDispatcher()) {
        val checker = checker(this)
        // Offline at resume: the next foreground asks again at once.
        server.queue(ApiError.Transport("offline"), ApiError.Server(code = "INTERNAL", serverMessage = "boom", status = 502), required(), current)
        checker.onForeground()
        clock.advanceBy(1_000)
        checker.onForeground()
        assertEquals(2, server.versions.size)
        clock.advanceBy(1_000)
        checker.onForeground()
        assertEquals(3, server.versions.size)
        assertTrue(checker.prompt.value is UpdatePrompt.Required)
        // Answered: quiet for ten minutes from the answer.
        clock.advanceBy(ClientUpdateChecker.FOREGROUND_INTERVAL_MS - 1)
        checker.onForeground()
        assertEquals(3, server.versions.size)
        clock.advanceBy(1)
        checker.onForeground()
        assertEquals(4, server.versions.size)
    }

    @Test
    fun theLimitRunsFromTheAnswerNotTheRequest() = runTest(UnconfinedTestDispatcher()) {
        val checker = checker(this)
        server.queue(current, current)
        val gate = CompletableDeferred<Unit>().also { server.gate = it }
        checker.onForeground()
        // A slow answer: resumes while it runs join it instead of asking again.
        clock.advanceBy(ClientUpdateChecker.FOREGROUND_INTERVAL_MS)
        checker.onForeground()
        assertEquals(1, server.versions.size)
        gate.complete(Unit)
        server.gate = null
        clock.advanceBy(ClientUpdateChecker.FOREGROUND_INTERVAL_MS - 1)
        checker.onForeground()
        assertEquals(1, server.versions.size)
        clock.advanceBy(1)
        checker.onForeground()
        assertEquals(2, server.versions.size)
    }

    @Test
    fun checkAgainIgnoresTheLimitAndJoinsARunningCheck() = runTest(UnconfinedTestDispatcher()) {
        val checker = checker(this)
        server.queue(required(), current)
        checker.onForeground()
        assertTrue(checker.prompt.value is UpdatePrompt.Required)
        val gate = CompletableDeferred<Unit>().also { server.gate = it }
        val first = checker.check()
        assertTrue(checker.isChecking.value)
        assertSame(first, checker.check())
        checker.onForeground()
        assertEquals(2, server.versions.size)
        gate.complete(Unit)
        first.join()
        assertFalse(checker.isChecking.value)
        // The server now says current: the blocking screen goes.
        assertEquals(UpdatePrompt.None, checker.prompt.value)
    }

    // ---- Failures ----

    @Test
    fun failuresKeepTheLastAnswer() = runTest(UnconfinedTestDispatcher()) {
        val checker = checker(this)
        server.queue(
            required(),
            ApiError.Transport("offline"),
            ApiError.Server(code = "INTERNAL", serverMessage = "boom", status = 500),
            ApiError.Decoding("bad json"),
        )
        checker.check()
        val shown = checker.prompt.value
        assertEquals(UpdatePrompt.Required("0.1.0", "0.3.0", "https://example.org/shroud"), shown)
        repeat(3) {
            checker.check().join()
            assertEquals(shown, checker.prompt.value)
            assertFalse(checker.isChecking.value)
        }
    }

    // ---- Later ----

    @Test
    fun laterSilencesThatVersionForThisProcessOnly() = runTest(UnconfinedTestDispatcher()) {
        val checker = checker(this)
        server.queue(available("0.2.0"), available("0.2.0"), available("0.2.1"))
        checker.check()
        assertEquals(UpdatePrompt.Available("0.1.0", "0.2.0", "https://example.org/shroud"), checker.prompt.value)
        checker.dismissAvailable(checker.shownOffer())
        assertEquals(UpdatePrompt.None, checker.prompt.value)
        // The same offer again: still quiet.
        checker.check()
        assertEquals(UpdatePrompt.None, checker.prompt.value)
        // A newer release offers again.
        checker.check()
        assertEquals(UpdatePrompt.Available("0.1.0", "0.2.1", "https://example.org/shroud"), checker.prompt.value)
        // A cold start (a new checker) offers the dismissed version again.
        server.answers += available("0.2.0")
        val coldStart = checker(this)
        coldStart.check()
        assertEquals(UpdatePrompt.Available("0.1.0", "0.2.0", "https://example.org/shroud"), coldStart.prompt.value)
    }

    @Test
    fun laterDoesNothingToARequiredUpdate() = runTest(UnconfinedTestDispatcher()) {
        val checker = checker(this)
        server.answers += required()
        checker.check()
        checker.dismissAvailable(UpdatePrompt.Available("0.1.0", "0.3.0", "https://example.org/shroud"))
        assertTrue(checker.prompt.value is UpdatePrompt.Required)
    }

    // ---- Server switch ----

    @Test
    fun aServerSwitchDropsTheOldAnswerAndAsksTheNewServer() = runTest(UnconfinedTestDispatcher()) {
        val checker = checker(this)
        server.queue(available("0.2.0"), required())
        checker.check()
        checker.dismissAvailable(checker.shownOffer())
        // The old server's slow answer must not land after the switch.
        val gate = CompletableDeferred<Unit>().also { server.gate = it }
        checker.check()
        server.answers += available("0.2.0")
        checker.onServerChanged()
        assertEquals(ClientUpdate.CURRENT, checker.update.value)
        assertTrue(checker.isChecking.value)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        // The new server's offer shows although "Later" was said to the old one.
        assertEquals(UpdatePrompt.Available("0.1.0", "0.2.0", "https://example.org/shroud"), checker.prompt.value)
        assertFalse(checker.isChecking.value)
        assertEquals(3, server.versions.size)
    }

    // ---- Outcomes ----

    @Test
    fun checkAgainSaysHowTheCheckEnded() = runTest(UnconfinedTestDispatcher()) {
        val checker = checker(this)
        server.queue(required(), ApiError.Transport("offline"), ClientVersionDto("beta_available", "9.9.9", null), available())
        assertEquals(UpdateCheckOutcome.Answered(ClientUpdateStatus.UpdateRequired), checker.checkAgain())
        assertEquals(UpdateCheckOutcome.Failed, checker.checkAgain())
        // The failure kept the block.
        assertTrue(checker.prompt.value is UpdatePrompt.Required)
        assertEquals(UpdateCheckOutcome.Answered(ClientUpdateStatus.Current), checker.checkAgain())
        assertEquals(UpdatePrompt.None, checker.prompt.value)
        assertEquals(UpdateCheckOutcome.Answered(ClientUpdateStatus.UpdateAvailable), checker.checkAgain())
    }

    @Test
    fun aCheckDroppedByAServerSwitchIsSkipped() = runTest(UnconfinedTestDispatcher()) {
        val checker = checker(this)
        server.queue(required(), current)
        val gate = CompletableDeferred<Unit>().also { server.gate = it }
        val outcome = async { checker.checkAgain() }
        checker.onServerChanged()
        gate.complete(Unit)
        assertEquals(UpdateCheckOutcome.Skipped, outcome.await())
        testScheduler.advanceUntilIdle()
        assertEquals(UpdatePrompt.None, checker.prompt.value)
    }

    @Test
    fun laterDismissesTheOfferTheDialogShowed() = runTest(UnconfinedTestDispatcher()) {
        val checker = checker(this)
        server.queue(available("0.2.0"), available("0.2.1"))
        checker.check()
        val onScreen = checker.shownOffer()
        // A newer answer lands before the tap: "Later" on the old dialog leaves the new offer.
        checker.check()
        checker.dismissAvailable(onScreen)
        assertEquals(UpdatePrompt.Available("0.1.0", "0.2.1", "https://example.org/shroud"), checker.prompt.value)
        checker.dismissAvailable(checker.shownOffer())
        assertEquals(UpdatePrompt.None, checker.prompt.value)
    }
}
