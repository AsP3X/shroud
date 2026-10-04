package de.corespace.shroud.core.update

import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.core.net.ClientVersionDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What the server says about this build (`GET /client-version`). */
enum class ClientUpdateStatus {
    Current,
    UpdateAvailable,
    UpdateRequired,
    ;

    companion object {
        /** The wire value; anything this build does not know reads as [Current]. */
        fun fromWire(value: String): ClientUpdateStatus = when (value) {
            "update_available" -> UpdateAvailable
            "update_required" -> UpdateRequired
            else -> Current
        }
    }
}

/**
 * The last answer of `GET /client-version`. [latestVersion] and [updateUrl] are null when the
 * operator set none; [updateUrl] is only ever an `http(s)` link.
 */
data class ClientUpdate(val status: ClientUpdateStatus, val latestVersion: String?, val updateUrl: String?) {
    companion object {
        val CURRENT = ClientUpdate(ClientUpdateStatus.Current, null, null)

        fun from(dto: ClientVersionDto): ClientUpdate {
            val status = ClientUpdateStatus.fromWire(dto.status)
            if (status == ClientUpdateStatus.Current) return CURRENT
            return ClientUpdate(status, dto.latestVersion?.trim()?.takeIf { it.isNotEmpty() }, updateLink(dto.updateUrl))
        }

        /** [raw] when it is an `http`/`https` URL (OkHttp parses no other scheme), else null. */
        internal fun updateLink(raw: String?): String? = raw?.trim()?.takeIf { it.toHttpUrlOrNull() != null }
    }
}

/** What the root draws for [ClientUpdateChecker.update]. */
sealed interface UpdatePrompt {
    /** Nothing: this build is current, or the user put the offered version off. */
    data object None : UpdatePrompt

    /** A newer release is out: the "Update available" dialog. */
    data class Available(val currentVersion: String, val latestVersion: String?, val updateUrl: String?) : UpdatePrompt

    /** This build no longer works with the server: the blocking "Update required" screen. */
    data class Required(val currentVersion: String, val latestVersion: String?, val updateUrl: String?) : UpdatePrompt

    companion object {
        /** The prompt for [update]; an offer whose latest version is in [dismissed] stays quiet. */
        fun of(update: ClientUpdate, currentVersion: String, dismissed: Set<String?>): UpdatePrompt = when (update.status) {
            ClientUpdateStatus.Current -> None
            ClientUpdateStatus.UpdateAvailable ->
                if (update.latestVersion in dismissed) None else Available(currentVersion, update.latestVersion, update.updateUrl)
            ClientUpdateStatus.UpdateRequired -> Required(currentVersion, update.latestVersion, update.updateUrl)
        }
    }
}

/** How one check ended ([ClientUpdateChecker.checkAgain]). */
sealed interface UpdateCheckOutcome {
    /** The server answered; [status] is what it said (unknown statuses read as current). */
    data class Answered(val status: ClientUpdateStatus) : UpdateCheckOutcome

    /** No usable answer (offline, a non-2xx, a body that does not decode); the last answer stays. */
    data object Failed : UpdateCheckOutcome

    /** Dropped: the server settings changed while it ran, so its answer belonged to the old server. */
    data object Skipped : UpdateCheckOutcome
}

/**
 * Asks the server this phone points at whether a newer release is out, and holds the answer for
 * the root's update prompts.
 *
 * - **No token** ([fetch] is `ShroudApi.clientVersion`): it works signed out, on the lock screen
 *   and before the chats are unlocked; the base URL is the one the server settings hold at the time
 *   of the call.
 * - **When**: the module calls [onForeground] each time one of our activities comes up from the
 *   background, the first time included (the app's start). A foreground check runs at most once
 *   per [FOREGROUND_INTERVAL_MS], counted from the last answer: a failed check (offline at resume)
 *   leaves the next foreground free to ask again, and a check still running is joined, so there
 *   is no burst. [check] and [checkAgain] (the required screen's "Check again", which also gets
 *   the [UpdateCheckOutcome]) ignore that limit.
 *   A server switch ([onServerChanged]) forgets everything and asks the new server at once.
 * - **Failures** (offline, a non-2xx, a body that does not decode) are ignored: the previous answer
 *   stays. A status this build does not know reads as current.
 * - **Later** ([dismissAvailable], with the offer the dialog showed) silences that one latest
 *   version for this process only: a newer release offers again, and so does the next cold start.
 *
 * Main-confined (00-plan §1.1 rule 3): [scope] is the app scope; [fetch] switches threads itself.
 */
class ClientUpdateChecker(
    val currentVersion: String,
    private val fetch: suspend (version: String) -> ClientVersionDto,
    private val clock: AppClock,
    private val scope: CoroutineScope,
) {
    private val updateState = MutableStateFlow(ClientUpdate.CURRENT)
    private val promptState = MutableStateFlow<UpdatePrompt>(UpdatePrompt.None)
    private val checkingState = MutableStateFlow(false)

    /** The latest versions the user answered "Later" for (null: an offer without a version). */
    private val dismissed = HashSet<String?>()
    /** When the last answer arrived (monotonic); failures leave it alone. */
    private var lastAnswerAt: Long? = null
    private var running: Deferred<UpdateCheckOutcome>? = null

    /** The server's last answer; [ClientUpdate.CURRENT] until one came. */
    val update: StateFlow<ClientUpdate> = updateState.asStateFlow()

    /** What the root shows: nothing, the dialog, or the blocking screen. */
    val prompt: StateFlow<UpdatePrompt> = promptState.asStateFlow()

    /** A check is on its way (the required screen's "Checking…"). */
    val isChecking: StateFlow<Boolean> = checkingState.asStateFlow()

    /** The app came to the foreground: checks unless the last answer is under 10 minutes old. */
    fun onForeground() {
        val last = lastAnswerAt
        if (last != null && clock.elapsedMillis() - last < FOREGROUND_INTERVAL_MS) return
        check()
    }

    /**
     * Checks now, whatever the limit says; a check already running is joined, not repeated. The
     * result is cancelled when a server switch drops the check ([checkAgain] reads that as
     * [UpdateCheckOutcome.Skipped]).
     */
    fun check(): Deferred<UpdateCheckOutcome> {
        running?.takeIf { it.isActive }?.let { return it }
        checkingState.value = true
        // Lazy: [running] is set before the body can finish, even when [fetch] does not suspend.
        val check = scope.async(start = CoroutineStart.LAZY) {
            try {
                val answer = try {
                    fetch(currentVersion)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                // A server switch cancels this check: its answer belongs to the old server.
                ensureActive()
                if (answer == null) {
                    UpdateCheckOutcome.Failed
                } else {
                    val update = ClientUpdate.from(answer)
                    lastAnswerAt = clock.elapsedMillis()
                    publish(update)
                    UpdateCheckOutcome.Answered(update.status)
                }
            } finally {
                if (running === coroutineContext.job) {
                    running = null
                    checkingState.value = false
                }
            }
        }
        running = check
        check.start()
        return check
    }

    /** [check], waited for: how it ended. A check a server switch dropped is [UpdateCheckOutcome.Skipped]. */
    suspend fun checkAgain(): UpdateCheckOutcome {
        val check = check()
        return try {
            check.await()
        } catch (e: CancellationException) {
            // The caller's own cancellation stays one; only the dropped check reads as skipped.
            currentCoroutineContext().ensureActive()
            UpdateCheckOutcome.Skipped
        }
    }

    /**
     * "Later" (or "OK") on [offer], the offer the dialog showed: quiet for its latest version until
     * the process ends. An answer that changed meanwhile is judged on its own version.
     */
    fun dismissAvailable(offer: UpdatePrompt.Available) {
        dismissed += offer.latestVersion
        publish(updateState.value)
    }

    /** The phone now talks to another server: drop what the old one said and ask the new one. */
    fun onServerChanged() {
        val stale = running
        running = null
        stale?.cancel()
        dismissed.clear()
        lastAnswerAt = null
        publish(ClientUpdate.CURRENT)
        check()
    }

    private fun publish(update: ClientUpdate) {
        updateState.value = update
        promptState.value = UpdatePrompt.of(update, currentVersion, dismissed)
    }

    companion object {
        /** The `platform` this app sends. */
        const val PLATFORM = "android"

        /** A foreground check waits this long after the last answer (10 minutes, monotonic). */
        const val FOREGROUND_INTERVAL_MS = 10 * 60 * 1000L
    }
}
