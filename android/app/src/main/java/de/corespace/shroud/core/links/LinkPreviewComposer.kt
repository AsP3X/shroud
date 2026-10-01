package de.corespace.shroud.core.links

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Composer-side link previews: watches the draft, fetches a preview for its first link and hands
 * the result to the send — iOS `LinkPreviewComposer`
 * (`ios/shroud/Services/Links/LinkPreviewComposer.swift:4-222`), web `linkPreview/composer.ts`,
 * media-voice-links §10.6.
 *
 * Telegram's behaviour step for step: typing or pasting a link shows "Loading preview…", then the
 * page's title and description; ✕ ([dismiss]) drops the preview for that link (typing a *different*
 * link brings one back); the strip's menu offers "Show Above Text", "Larger / Smaller Image" and
 * "Remove Preview" (copy owned by the composer UI). A preview that has not finished loading when
 * Send is tapped is simply left off — sending never waits on a website.
 *
 * Owned by the conversation screen (one per open chat, [de.corespace.shroud.di.LinksModule.newComposer]);
 * confined to the main thread like iOS's `@MainActor` model — call every method from [scope]'s
 * thread. The only network access is the injected [fetcher]; holds no key material and persists
 * nothing. [reset] after a send and when leaving the chat — but not when a pushed contact profile
 * will return to the same draft (`ConversationView.swift:367-368`).
 *
 * @param isEnabled Settings → Privacy → link previews ([LinkPreviewSettings.enabled]), read on
 *   every [draftChanged] that does not pass `enabled` itself.
 * @param debounceMs how long the typist must pause before a fetch starts (Telegram fires on a
 *   similar beat); tests pass 0.
 */
class LinkPreviewComposer(
    private val fetcher: LinkPreviewFetching,
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean = { true },
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    /** Where the strip is (`LinkPreviewComposer.swift:17-21`). */
    sealed interface Phase {
        data object Idle : Phase

        /** Fetching [url] (the link as the detector found it). */
        data class Loading(val url: String) : Phase {
            /** Never prints the link: drafts are message content. */
            override fun toString(): String = "Loading"
        }

        data class Ready(val draft: LinkPreviewDraft) : Phase
    }

    /** Everything the strip draws, published as one value. */
    data class State(
        val phase: Phase = Phase.Idle,
        /** Telegram's "Show above message". */
        val showsAboveText: Boolean = false,
        /** The user's size choice; null follows the page's default. */
        val largeImageOverride: Boolean? = null,
    ) {
        /** The loaded preview, if any. */
        val draft: LinkPreviewDraft? get() = (phase as? Phase.Ready)?.draft

        /** Whether the large layout is in effect for the loaded preview (`LinkPreviewComposer.swift:51-55`). */
        val usesLargeImage: Boolean
            get() {
                val draft = draft ?: return false
                if (draft.largeImage == null) return false
                return largeImageOverride ?: draft.prefersLargeImage
            }

        /** "Larger / Smaller Image" only makes sense when both layouts exist (`:57-61`). */
        val canToggleImageSize: Boolean
            get() {
                val draft = draft ?: return false
                return draft.largeImage != null && draft.preview.thumbnail != null
            }
    }

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    val phase: Phase get() = mutableState.value.phase
    val draft: LinkPreviewDraft? get() = mutableState.value.draft
    val showsAboveText: Boolean get() = mutableState.value.showsAboveText
    val usesLargeImage: Boolean get() = mutableState.value.usesLargeImage
    val canToggleImageSize: Boolean get() = mutableState.value.canToggleImageSize

    private var fetchJob: Job? = null
    private var debounceJob: Job? = null

    /** Links the user closed with ✕ during this draft. */
    private val dismissed = HashSet<String>()

    /** Recent results, oldest first, so deleting and re-typing a character never refetches. */
    private val cache = LinkedHashMap<String, LinkPreviewDraft>()

    // ---- Draft tracking ---------------------------------------------------------------------------

    /**
     * Call on every draft change (`LinkPreviewComposer.swift:65-97`). Debounced; cancels a fetch for
     * a link that is no longer the first one in the text. Switched off (or no link): nothing is
     * fetched and the strip goes away; dismissed links survive while the draft has text.
     */
    fun draftChanged(text: String, enabled: Boolean = isEnabled()) {
        debounceJob?.cancel()
        debounceJob = null
        val url = if (enabled) LinkDetector.firstPreviewableUrl(text) else null
        if (url == null) {
            clear(keepDismissed = text.isNotEmpty())
            return
        }
        val key = key(url)
        if (key in dismissed) {
            cancelFetch()
            setPhase(Phase.Idle)
            return
        }
        // Already showing (or fetching) this link: nothing to do.
        when (val current = phase) {
            is Phase.Loading -> if (key(current.url) == key) return
            is Phase.Ready -> if (key(current.draft.preview.url) == key) return
            Phase.Idle -> Unit
        }
        cache[key]?.let { cached ->
            cancelFetch()
            mutableState.update { it.copy(phase = Phase.Ready(cached), showsAboveText = false, largeImageOverride = null) }
            return
        }
        debounceJob = scope.launch {
            delay(debounceMs)
            debounceJob = null
            startFetch(url)
        }
    }

    /** `LinkPreviewComposer.swift:99-116`: a result only lands while the same link is still loading. */
    private fun startFetch(url: String) {
        cancelFetch()
        val key = key(url)
        mutableState.update { it.copy(phase = Phase.Loading(url), showsAboveText = false, largeImageOverride = null) }
        fetchJob = scope.launch {
            val result = try {
                fetcher.fetchPreview(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (!isActive) return@launch
            val current = phase
            if (current !is Phase.Loading || key(current.url) != key) return@launch
            if (result != null) {
                remember(key, result)
                setPhase(Phase.Ready(result))
            } else {
                // Telegram shows nothing when a page has no preview.
                setPhase(Phase.Idle)
            }
        }
    }

    // ---- Options ----------------------------------------------------------------------------------

    /** ✕ / "Remove Preview": no preview for this link for the rest of the draft (`:120-130`). */
    fun dismiss() {
        when (val current = phase) {
            is Phase.Loading -> dismissed += key(current.url)
            is Phase.Ready -> dismissed += key(current.draft.preview.url)
            Phase.Idle -> Unit
        }
        cancelFetch()
        setPhase(Phase.Idle)
    }

    /** "Show Above Text" / "Show Below Text" (`:132-134`). */
    fun toggleShowsAboveText() {
        mutableState.update { it.copy(showsAboveText = !it.showsAboveText) }
    }

    /** "Larger Image" / "Smaller Image", only when both layouts exist (`:136-139`). */
    fun toggleImageSize() {
        mutableState.update { state ->
            if (!state.canToggleImageSize) state else state.copy(largeImageOverride = !state.usesLargeImage)
        }
    }

    // ---- Sending ----------------------------------------------------------------------------------

    /**
     * What to seal with the message about to be sent, then back to idle for the next draft
     * (`:143-161`): null unless the loaded preview's link is still the first previewable link of
     * [text]. The large image goes along only while the large layout is chosen.
     */
    fun takeAttachment(text: String): LinkPreviewAttachment? {
        val snapshot = mutableState.value
        reset()
        val draft = snapshot.draft ?: return null
        val url = LinkDetector.firstPreviewableUrl(text) ?: return null
        if (key(url) != key(draft.preview.url)) return null
        val large = snapshot.usesLargeImage
        return LinkPreviewAttachment(
            preview = draft.preview.withShowsAboveText(snapshot.showsAboveText),
            largeImage = if (large) draft.largeImage else null,
            largeImageWidth = if (large) draft.largeImageWidth else null,
            largeImageHeight = if (large) draft.largeImageHeight else null,
        )
    }

    /** Clears everything, including dismissed links — after a send, or leaving the chat (`:163-167`). */
    fun reset() {
        debounceJob?.cancel()
        debounceJob = null
        clear(keepDismissed = false)
    }

    // ---- Private ----------------------------------------------------------------------------------

    private fun clear(keepDismissed: Boolean) {
        cancelFetch()
        if (!keepDismissed) dismissed.clear()
        mutableState.update { it.copy(phase = Phase.Idle, showsAboveText = false, largeImageOverride = null) }
    }

    private fun cancelFetch() {
        fetchJob?.cancel()
        fetchJob = null
    }

    private fun setPhase(phase: Phase) {
        mutableState.update { if (it.phase == phase) it else it.copy(phase = phase) }
    }

    private fun remember(key: String, draft: LinkPreviewDraft) {
        cache.remove(key)
        cache[key] = draft
        while (cache.size > CACHE_LIMIT) cache.remove(cache.keys.first())
    }

    companion object {
        /** Debounce before a fetch (`LinkPreviewComposer.swift:40`). */
        const val DEBOUNCE_MS = 450L

        /** Drafts remembered per composer (`:197`). */
        const val CACHE_LIMIT = 8

        /**
         * Identity of a link (`LinkPreviewComposer.swift:202-221`, web `linkKey`): scheme and host
         * lowercased, everything else as typed — `youtu.be/dQw4w9WgXcQ` and `youtu.be/dqw4w9wgxcq` are
         * different videos, so lowercasing the whole URL would hand one link the other's preview.
         * OkHttp's canonical form does exactly that (and drops a default port); anything it cannot
         * parse is its own key.
         */
        internal fun key(url: String): String = url.toHttpUrlOrNull()?.toString() ?: url
    }
}
