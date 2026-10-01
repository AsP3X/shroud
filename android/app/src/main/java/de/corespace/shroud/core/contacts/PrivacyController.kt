package de.corespace.shroud.core.contacts

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.auth.SessionController
import de.corespace.shroud.core.net.PrivacySettingsDto
import de.corespace.shroud.core.net.UpdatePrivacySettingsBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The account's privacy switches and its share code (iOS `MessagingController.swift:86-92,
 * 2093-2180`; messaging-core §20.2; contacts §4.4; plan C6).
 *
 * [settings] moves only once the server confirms a change, so a switch never lies; before the first
 * load (and after [reset]) it holds the server's defaults — no chat-delete consent, everything
 * visible (`PrivacySettingsDto(allowPeerChatDelete = false)`, `:86`). A change of `share_presence`
 * clears the contacts' presence and re-sweeps when shown again ([onSharePresenceChanged], `:2172-2179`).
 * The other consequences of a change are messaging's (`adoptPrivacySettings`, `:2156-2180`):
 * `MessagingController` collects [settings] and, on `sendReadReceipts` turning off, steps read ticks
 * back to delivered (`hideReadTicks`); on `sendTyping` turning off, stops its own typing/recording
 * signals and clears the peers' indicators.
 *
 * Main-confined like the other controllers: the suspend members switch to [main] themselves.
 * Refreshes and resets follow the contacts engine ([ContactsLifecycleListener]): a refresh when it
 * starts or comes to the front (`:464-487, 598-626`), a [reset] when it stops (`stopActivity`,
 * `:541-543`). Overlapping refreshes share one request.
 *
 * @param revalidate `SessionController.validate()`: `/auth/me`, which brings the rotated code into the session.
 * @param setLastError messaging's `lastError` (`ContactsHooks.setLastError`), when bound.
 */
class PrivacyController(
    private val backend: ContactsBackend,
    private val session: () -> Session?,
    private val revalidate: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val main: CoroutineContext = Dispatchers.Main.immediate,
    private val onSharePresenceChanged: (Boolean) -> Unit = {},
    private val setLastError: (String?) -> Unit = {},
) : Privacy, ContactsLifecycleListener {
    private val settingsState = MutableStateFlow(DEFAULT)
    override val settings: StateFlow<PrivacySettingsDto> = settingsState.asStateFlow()

    private val loaded = MutableStateFlow(false)
    override val hasLoaded: StateFlow<Boolean> = loaded.asStateFlow()

    private var inFlight: Deferred<Unit>? = null

    /** Bumped by [reset]: answers to requests made before it are dropped. */
    private var generation = 0L

    /**
     * Loads the switches (`refreshPrivacySettings`, `:2097-2102`). Silent on failure: an unreachable
     * server must not flip a consent switch, so the last known values stand.
     */
    override suspend fun refresh(): Unit = withContext(main) {
        inFlight?.let {
            it.join()
            return@withContext
        }
        val job = scope.async(main) { performRefresh() }
        inFlight = job
        try {
            job.join()
        } finally {
            if (inFlight === job) inFlight = null
        }
    }

    private suspend fun performRefresh() {
        val bearer = session()?.token ?: return
        val gen = generation
        val fresh = try {
            backend.privacySettings(bearer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        if (gen != generation) return
        adopt(fresh)
        loaded.value = true
    }

    /**
     * Writes the fields set in [change] (`updatePrivacySettings`, `:2111-2127`) — only those go on
     * the wire (`ShroudApi.patchJson`) — and adopts what the server stored. Null on success, else the
     * text to show.
     */
    override suspend fun update(change: UpdatePrivacySettingsBody): String? = withContext(main) {
        val bearer = session()?.token ?: return@withContext "Sign in to change privacy settings."
        val gen = generation
        try {
            val stored = backend.updatePrivacySettings(bearer, change)
            if (gen == generation) {
                adopt(stored)
                loaded.value = true
            }
            setLastError(null)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SessionController.userMessage(e).also(setLastError)
        }
    }

    /** The chat-delete consent (`setAllowsPeerChatDelete`, `:2105-2107`). */
    override suspend fun setAllowsPeerChatDelete(value: Boolean): String? =
        update(UpdatePrivacySettingsBody(allowPeerChatDelete = value))

    /**
     * Replaces the account's share code, so codes and links handed out so far stop working
     * (`rotateShareCode`, `:2130-2151`). The new code reaches the session through `/auth/me`; when it
     * did not (offline right after the rotation), says so instead of showing the dead code as current.
     */
    override suspend fun rotateShareCode(): String? = withContext(main) {
        val bearer = session()?.token ?: return@withContext "Sign in to reset your QR code."
        try {
            val code = backend.rotateShareCode(bearer)
            revalidate()
            setLastError(null)
            if (session()?.shareCode != code) "Your QR code was reset. It shows here once Shroud reconnects." else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SessionController.userMessage(e).also(setLastError)
        }
    }

    /** Back to the defaults, not loaded (`stopActivity`, `:541-543`); main thread. */
    override fun reset() {
        generation++
        inFlight = null
        settingsState.value = DEFAULT
        loaded.value = false
    }

    override fun onContactsActive() {
        scope.launch(main) { refresh() }
    }

    override fun onContactsStopped(wipe: Boolean) = reset()

    /** `adoptPrivacySettings` (`:2156-2180`), the part this package owns. */
    private fun adopt(fresh: PrivacySettingsDto) {
        val previous = settingsState.value
        if (fresh == previous) return
        settingsState.value = fresh
        if (previous.sharePresence != fresh.sharePresence) onSharePresenceChanged(fresh.sharePresence)
    }

    companion object {
        /** What a server predating the switches means, and the state before the first load (`:86`). */
        val DEFAULT = PrivacySettingsDto(allowPeerChatDelete = false)
    }
}
