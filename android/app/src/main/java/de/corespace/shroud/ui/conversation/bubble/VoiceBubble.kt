package de.corespace.shroud.ui.conversation.bubble

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.transcription.TranscriptionInstallState
import de.corespace.shroud.core.voice.VoicePlaybackState
import de.corespace.shroud.core.voice.VoiceTimeFormat
import de.corespace.shroud.core.voice.VoiceWaveform
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.shimmering
import de.corespace.shroud.ui.conversation.BubbleContext
import de.corespace.shroud.ui.conversation.reactions.reactionAccessibilityActions
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The voice bubble's numbers (`VoiceMessageBubble.swift:106-178`; conversation-thread §11.2), pure and
 * unit-tested. All widths in dp.
 */
object VoiceBubbleMath {
    /** Notes this short draw the narrowest waveform; this long and longer the widest (`:110-111`). */
    const val SHORT_NOTE_SECONDS = 2.0
    const val LONG_NOTE_SECONDS = 14.0

    /** Narrowest waveform: below it the footer and a short transcript crowd (`:112-114`). */
    const val MIN_WAVEFORM_WIDTH = 160f

    /** 3 dp bar + 2 dp gap, [VoiceWaveformView]'s defaults (`:115-116`). */
    const val BAR_PITCH = 5f
    const val PLAY_BUTTON_SIZE = 38f
    const val PLAY_BUTTON_GAP = 10f
    const val TRANSCRIPT_BUTTON_GAP = 8f
    const val HORIZONTAL_PADDING = 10f
    const val TRANSCRIPT_INSET = 2f
    const val TRANSCRIPT_BUTTON_SIZE = 28f

    /** Below this the payload's duration is bogus rather than a real recording (`:69-71`). */
    const val MINIMUM_TRUSTED_DURATION_MS = 300

    /** Everything in the waveform row besides the waveform (`:125-129`): 2·10 + 38 + 10 + 8 + 28 = 104. */
    const val WAVEFORM_CHROME = HORIZONTAL_PADDING * 2 + PLAY_BUTTON_SIZE + PLAY_BUTTON_GAP + TRANSCRIPT_BUTTON_GAP + TRANSCRIPT_BUTTON_SIZE

    /** The payload's duration, or the one measured off the audio when the payload's is bogus (`:73-83`). */
    fun durationMs(stated: Int?, resolved: Int?): Int {
        val value = stated ?: 0
        if (value >= MINIMUM_TRUSTED_DURATION_MS) return value
        return resolved ?: value
    }

    /**
     * The waveform's width (`:131-141`): it ramps with the duration from 160 dp at 2 s to the width of a
     * long text bubble (less the chrome) at 14 s, in whole bars.
     */
    fun waveformWidth(durationMs: Int, maxBubbleWidth: Float): Float {
        val seconds = durationMs / 1000.0
        val ramp = (seconds - SHORT_NOTE_SECONDS) / (LONG_NOTE_SECONDS - SHORT_NOTE_SECONDS)
        val ceiling = max(MIN_WAVEFORM_WIDTH, maxBubbleWidth - WAVEFORM_CHROME)
        val width = MIN_WAVEFORM_WIDTH + (ceiling - MIN_WAVEFORM_WIDTH) * min(1.0, max(0.0, ramp)).toFloat()
        return floor(width / BAR_PITCH) * BAR_PITCH
    }

    fun barCount(waveformWidth: Float): Int = (waveformWidth / BAR_PITCH).toInt()

    /** Waveform plus the transcript toggle beside it; the footer spans both (`:151-154`). */
    fun columnWidth(waveformWidth: Float, showsTranscriptButton: Boolean): Float =
        waveformWidth + if (showsTranscriptButton) TRANSCRIPT_BUTTON_GAP + TRANSCRIPT_BUTTON_SIZE else 0f

    /** The row under the quote: play disc, gap, column (`:259`). */
    fun contentWidth(columnWidth: Float): Float = PLAY_BUTTON_SIZE + PLAY_BUTTON_GAP + columnWidth

    /** The unfolded transcript wraps at the bubble's own width and never widens it (`:163-166`). */
    fun transcriptWidth(columnWidth: Float): Float = contentWidth(columnWidth) - TRANSCRIPT_INSET * 2

    /** "1×", "1.5×", "2×": whole rates without decimals, else one (`:504-507`). */
    fun rateLabel(rate: Float): String =
        if (rate == rate.roundToInt().toFloat()) "${rate.roundToInt()}×" else String.format(Locale.ROOT, "%.1f×", rate)

    /**
     * The drawer's progress line (`:647-660`): the model download with its language and percent (percent
     * only when determinate and above 0), else "Transcribing…".
     */
    fun progressLabel(downloading: Boolean, fraction: Double, isDeterminate: Boolean, languageName: String?): String {
        if (!downloading) return "Transcribing…"
        val percent = (fraction * 100).roundToInt()
        val showsPercent = isDeterminate && percent > 0
        return if (languageName != null) {
            if (showsPercent) "Downloading $languageName… $percent%" else "Downloading $languageName…"
        } else {
            if (showsPercent) "Downloading model… $percent%" else "Downloading model…"
        }
    }

    /** "Hide transcript" / "Transcribe" / "Show transcript" (`:579-582`). */
    fun transcriptActionName(isOpen: Boolean, hasTranscript: Boolean, isWorking: Boolean): String = when {
        isOpen -> "Hide transcript"
        !hasTranscript && !isWorking -> "Transcribe"
        else -> "Show transcript"
    }

    /** Fresh off the network or the recorder: younger than the arrival window (`:558-566`). */
    fun isFresh(createdAt: Instant, now: Instant): Boolean =
        Duration.between(createdAt, now).toMillis() < VoiceTranscriptDisclosure.ARRIVAL_WINDOW_MS
}

/** What one voice bubble reads of the app-wide player, so the others do not recompose on its ticks. */
@Immutable
private data class VoiceRowPlayback(
    val isActive: Boolean,
    val isPlaying: Boolean,
    val progress: Double,
    val elapsedSeconds: Double?,
    val rate: Float,
    val hasPlayed: Boolean,
) {
    companion object {
        fun of(state: VoicePlaybackState, id: java.util.UUID): VoiceRowPlayback {
            val active = state.activeId == id
            return VoiceRowPlayback(
                isActive = active,
                isPlaying = active && state.isPlaying,
                progress = if (active && state.duration > 0) min(1.0, max(0.0, state.currentTime / state.duration)) else 0.0,
                elapsedSeconds = if (active) state.currentTime else null,
                rate = state.rate,
                hasPlayed = id in state.playedIds,
            )
        }
    }
}

/** What the transcript drawer holds (`VoiceMessageBubble.swift:529-540`). */
private sealed interface DrawerContent {
    data class Text(val text: String) : DrawerContent
    data object Working : DrawerContent
    data object NoSpeech : DrawerContent
}

/**
 * Telegram's voice bubble — iOS `VoiceMessageBubble` (`VoiceMessageBubble.swift:14-758`;
 * conversation-thread §11): play/pause disc, scrubbable waveform, the "→A" transcript toggle, the
 * elapsed / duration readout, the unplayed dot, the speed chip, and the transcript folded inside.
 *
 * The bubble owns no player: transport goes through the app-wide `VoicePlaybackCoordinator`
 * ([BubbleServices.playback]), so a playing note keeps playing when scrolled away and starting another
 * stops it. The audio is fetched and decrypted on appear ([BubbleServices.ensureVoiceLoaded]); when that
 * attempt ends without it, the disc offers a retry. Bytes are read from the sealed cache only to start
 * a note (plan C8). The fold lives in [VoiceTranscriptDisclosure] (shared with the menu hero): the newest
 * notes unfold a short transcript by themselves once landed; a tap on "→A" folds, unfolds, or first
 * transcribes on this phone ([BubbleServices.transcribe]).
 *
 * The time and ticks are always the last thing in the bubble: in the footer of a bare note, at the end
 * of the unfolded transcript's last line, closing the reaction row. iOS slides them between those places
 * (`matchedGeometryEffect`); here they move with the bubble's size animation.
 */
@Composable
internal fun VoiceMessageBubble(parts: BubbleParts, context: BubbleContext, services: BubbleServices, modifier: Modifier) {
    val message = parts.message
    val id = message.id
    val isMine = message.isMine
    val handlers = parts.handlers
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    val scope = rememberCoroutineScope()
    val toaster = LocalBubbleToaster.current
    val revealsArrival = parts.embedded

    val playbackState = services.playback.state.collectAsState()
    val playback by remember(id, playbackState) { derivedStateOf { VoiceRowPlayback.of(playbackState.value, id) } }
    val installState = services.transcription.install.collectAsState()
    val installActive by remember(id, installState) {
        derivedStateOf { installState.value.let { it.phase != TranscriptionInstallState.Phase.Idle && it.messageId == id } }
    }

    var localTranscript by remember(id) { mutableStateOf<String?>(null) }
    var isTranscribing by remember(id) { mutableStateOf(false) }
    var foundNoSpeech by remember(id) { mutableStateOf(false) }
    var scrubProgress by remember(id) { mutableStateOf<Double?>(null) }
    var resolvedDurationMs by remember(id) { mutableStateOf<Int?>(null) }
    var isLanding by remember(id) { mutableStateOf<Boolean?>(null) }
    var hasAppeared by remember(id) { mutableStateOf(false) }
    var loadFailed by remember(id) { mutableStateOf(false) }

    val transcript = listOf(localTranscript, message.transcript).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
    val durationMs = VoiceBubbleMath.durationMs(message.durationMs, resolvedDurationMs)
    val needsAudio = !message.hasFullMedia && message.mediaObjectId != null && !message.deleted
    val isLoading = needsAudio && !loadFailed
    val progress = scrubProgress ?: playback.progress
    val showsUnplayedDot = !isMine && !playback.hasPlayed

    // Sizing (`:118-166`).
    val waveformWidth = VoiceBubbleMath.waveformWidth(durationMs, parts.maxBubbleWidth.value)
    val barCount = VoiceBubbleMath.barCount(waveformWidth)
    val showsTranscriptButton = !message.deleted
    val columnWidth = VoiceBubbleMath.columnWidth(waveformWidth, showsTranscriptButton)
    val contentWidth = VoiceBubbleMath.contentWidth(columnWidth)
    val transcriptWidth = VoiceBubbleMath.transcriptWidth(columnWidth)
    val samples = remember(id, message.voiceWaveform, barCount) { VoiceWaveform.bubbleSamples(id, message.voiceWaveform, barCount).toList() }

    // Transcript state (`:511-566`).
    val isWorking = isTranscribing || installActive
    val drawer: DrawerContent? = when {
        transcript != null -> DrawerContent.Text(transcript)
        isWorking -> DrawerContent.Working
        foundNoSpeech -> DrawerContent.NoSpeech
        else -> null
    }
    val landing = isLanding ?: (
        revealsArrival &&
            !VoiceTranscriptDisclosure.wasHandedOff(id) &&
            !VoiceTranscriptDisclosure.hasLanded(id) &&
            VoiceBubbleMath.isFresh(message.createdAt, Instant.now())
        )
    val opensUnasked = parts.row.showsTranscriptTail && !landing &&
        VoiceTranscriptDisclosure.opensUnasked(transcript, durationMs, isWorking)
    val isTranscriptOpen = drawer != null && (VoiceTranscriptDisclosure.choice(id) ?: opensUnasked)
    val canTranscribe = handlers.interactive
    val transcriptEnabled = drawer != null || (canTranscribe && message.hasFullMedia)
    val showsReactions = parts.chips.isNotEmpty() && !message.deleted
    val metaInFooter = !isTranscriptOpen && !showsReactions
    val metaInTranscript = isTranscriptOpen && !showsReactions

    // Palette (`:180-190`).
    val played = if (isMine) Color.White else colors.accent
    val remaining = if (isMine) Color.White.copy(alpha = 0.4f) else colors.accent.copy(alpha = 0.28f)
    val metaColor = if (isMine) Color.White.copy(alpha = 0.7f) else colors.textSecondary

    // Lifecycle (`:204-227`).
    if (parts.embedded) {
        LaunchedEffect(id) {
            hasAppeared = true
            loadFailed = false
            services.ensureVoiceLoaded(message)
            // A successful load set the full media in the same turn: the flag goes unread then.
            loadFailed = true
        }
        LaunchedEffect(id) {
            // A note that arrives while the thread is open lands folded, then unfolds (`:208-219`).
            if (isLanding != null) return@LaunchedEffect
            val shouldLand = landing
            isLanding = shouldLand
            if (!shouldLand) return@LaunchedEffect
            try {
                if (!reduceMotion) delay(VoiceTranscriptDisclosure.LANDING_DELAY_MS)
            } finally {
                isLanding = false
                VoiceTranscriptDisclosure.markLanded(id)
            }
        }
        val currentPlayback by rememberUpdatedState(services.playback)
        DisposableEffect(id) {
            onDispose {
                // A paused note this bubble owns is torn down; a playing one keeps going (`:221-227`).
                val coordinator = currentPlayback
                if (coordinator.isActive(id) && !coordinator.state.value.isPlaying) coordinator.stopIfActive(id)
            }
        }
    }
    LaunchedEffect(id, message.hasFullMedia) {
        // Old payloads say `d = 1`: read the length off the audio (`:694-708`).
        if ((message.durationMs ?: 0) >= VoiceBubbleMath.MINIMUM_TRUSTED_DURATION_MS || resolvedDurationMs != null || !message.hasFullMedia) return@LaunchedEffect
        resolvedDurationMs = services.mediaDurationMs(id)
    }

    fun loadAudio() {
        if (!parts.embedded) return
        loadFailed = false
        scope.launch {
            services.ensureVoiceLoaded(message)
            loadFailed = true
        }
    }

    // Transport (`:710-733`): starting a note reads its audio from the sealed cache.
    fun togglePlayback() {
        if (!handlers.interactive) return
        if (!message.hasFullMedia) {
            loadAudio()
            return
        }
        handlers.haptic(Haptic.Light)
        val coordinator = services.playback
        if (coordinator.isActive(id)) {
            coordinator.toggle(id, ByteArray(0))
            return
        }
        scope.launch {
            val audio = services.mediaBytes(id)
            if (audio == null) loadAudio() else coordinator.toggle(id, audio)
        }
    }

    fun toggleTranscript() {
        if (isTranscriptOpen) {
            VoiceTranscriptDisclosure.setOpen(false, id)
            context.onTranscriptToggled(message, expanded = false)
            return
        }
        val needsTranscript = drawer == null
        // The long-press hero has no transcriber; with nothing to show there is nothing to open (`:670-671`).
        if (needsTranscript && !canTranscribe) return
        VoiceTranscriptDisclosure.setOpen(true, id)
        context.onTranscriptToggled(message, expanded = true)
        if (!needsTranscript) return
        isTranscribing = true
        scope.launch {
            val result = try {
                services.transcribe(message, parts.row.peerName)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // The failure is the user's to read (`ConversationView.swift:1625-1629`).
                toaster(Toast.failure(e.message ?: "Could not transcribe that voice message."))
                handlers.haptic(Haptic.Error)
                null
            }
            isTranscribing = false
            if (result != null) {
                localTranscript = result
                foundNoSpeech = result.isBlank()
            } else {
                // Failed, and the toast said why: fold back to a plain "→A".
                VoiceTranscriptDisclosure.setOpen(false, id)
            }
        }
    }

    val quoteTap = handlers.quoteTap(message)
    val reply = parts.row.replyQuote
    val rateLabel = VoiceBubbleMath.rateLabel(playback.rate)
    val playActionName = if (needsAudio && loadFailed) "Retry download" else if (playback.isPlaying) "Pause" else "Play"
    val label = BubbleAccessibility.voice(
        isMine = isMine,
        reply = reply,
        spokenDuration = VoiceTimeFormat.spoken(durationMs / 1000.0),
        unplayed = showsUnplayedDot,
        downloadFailed = needsAudio && loadFailed,
        transcript = transcript,
        deleted = message.deleted,
        chips = parts.chips,
        time = parts.time,
        receipt = message.receipt,
    )
    val rowActions = LocalMessageRowActions.current
    val actions = buildList {
        add(CustomAccessibilityAction(playActionName) { togglePlayback(); true })
        if (showsTranscriptButton && transcriptEnabled && handlers.interactive) {
            add(CustomAccessibilityAction(VoiceBubbleMath.transcriptActionName(isTranscriptOpen, transcript != null, isWorking)) { toggleTranscript(); true })
        }
        if (reply != null && quoteTap != null) add(CustomAccessibilityAction("Show replied message") { quoteTap(); true })
        if (playback.isActive && handlers.interactive) {
            add(CustomAccessibilityAction("Playback speed $rateLabel") { services.playback.cycleRate(); true })
        }
        if (!message.deleted) addAll(reactionAccessibilityActions(parts.chips, parts.onReaction))
        addAll(rowActions)
    }

    val meta: @Composable () -> Unit = {
        BubbleMetaRow(
            time = parts.time,
            receipt = if (isMine) message.receipt else null,
            metaColor = metaColor,
            readColor = Color.White,
            failedColor = Color.White,
            // Ticks on our bubble: white @0.7, read white, failed white (`:461-468`).
            timeColor = metaColor,
        )
    }
    val shape = BubbleShapes.tail(isMine)
    val standard: FiniteAnimationSpec<androidx.compose.ui.unit.IntSize> = Motion.respecting(reduceMotion, Motion.standard())
    // The drawer rises 6 dp as it fades in (`:283-286`).
    val riseOffset = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }

    val bubble = @Composable {
        Box(
            Modifier
                .then(parts.reportBounds)
                .then(
                    if (isMine) {
                        Modifier
                    } else {
                        Modifier.dropShadow(shape, Shadow(radius = 3.dp, color = Color.Black, offset = DpOffset(0.dp, 1.dp), alpha = 0.04f))
                    },
                )
                .clip(shape)
                .background(if (isMine) colors.bubbleOutgoing else colors.bubbleIncoming)
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    // The play disc is hidden: a double tap plays or pauses (`:231-235`).
                    if (handlers.interactive) {
                        onClick(label = playActionName) {
                            togglePlayback()
                            true
                        }
                    }
                    customActions = actions
                }
                .animateContentSize(standard)
                .padding(horizontal = VoiceBubbleMath.HORIZONTAL_PADDING.dp, vertical = 8.dp),
        ) {
            Column {
                if (reply != null) {
                    Box(Modifier.width(contentWidth.dp).padding(bottom = 5.dp)) {
                        ReplyQuoteBlock(reply, if (isMine) ReplyQuoteStyle.Outgoing else ReplyQuoteStyle.Incoming, quoteTap, REPLY_FONT, Modifier)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(VoiceBubbleMath.PLAY_BUTTON_GAP.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayButton(
                        isMine = isMine,
                        glyph = when {
                            isLoading -> PlayGlyph.Loading
                            needsAudio -> PlayGlyph.Retry
                            playback.isPlaying -> PlayGlyph.Pause
                            else -> PlayGlyph.Play
                        },
                        enabled = handlers.interactive && !isLoading && (message.hasFullMedia || needsAudio),
                        onClick = { handlers.run { togglePlayback() } },
                    )
                    Column(Modifier.width(columnWidth.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(VoiceBubbleMath.TRANSCRIPT_BUTTON_GAP.dp), verticalAlignment = Alignment.CenterVertically) {
                            ScrubbableWaveform(
                                samples = samples,
                                progress = progress,
                                played = played,
                                remaining = remaining,
                                width = waveformWidth.dp,
                                enabled = playback.isActive && message.hasFullMedia && handlers.interactive,
                                onScrub = { scrubProgress = it },
                                onSeek = { fraction ->
                                    scrubProgress = null
                                    // Only the active note scrubs, so the player has its audio already; a note
                                    // another one replaced mid-drag is left alone (its bytes are not at hand).
                                    if (services.playback.isActive(id)) {
                                        handlers.haptic(Haptic.Light)
                                        services.playback.seek(id, ByteArray(0), fraction)
                                    }
                                },
                            )
                            if (showsTranscriptButton) {
                                VoiceTranscriptButton(
                                    isOpen = isTranscriptOpen,
                                    isWorking = isWorking,
                                    ink = if (isMine) Color.White else colors.accentText,
                                    fill = if (isMine) Color.White.copy(alpha = 0.2f) else colors.accent.copy(alpha = 0.12f),
                                    isEnabled = transcriptEnabled,
                                    onClick = { handlers.run { toggleTranscript() } },
                                )
                            }
                        }
                        VoiceFooter(
                            time = VoiceTimeFormat.duration(playback.elapsedSeconds ?: (durationMs / 1000.0)),
                            metaColor = metaColor,
                            showsUnplayedDot = showsUnplayedDot,
                            speed = if (playback.isActive) rateLabel else null,
                            isMine = isMine,
                            onSpeed = {
                                handlers.run {
                                    handlers.haptic(Haptic.Light)
                                    services.playback.cycleRate()
                                }
                            },
                            meta = if (metaInFooter) meta else null,
                        )
                    }
                }
                AnimatedVisibility(
                    visible = isTranscriptOpen,
                    enter = fadeIn(Motion.respecting(reduceMotion, Motion.standard())) +
                        expandVertically(Motion.respecting(reduceMotion, Motion.standard()), expandFrom = Alignment.Top) +
                        slideInVertically(Motion.respecting(reduceMotion, Motion.standard())) { -riseOffset },
                    exit = fadeOut(Motion.respecting(reduceMotion, Motion.standard())) +
                        shrinkVertically(Motion.respecting(reduceMotion, Motion.standard()), shrinkTowards = Alignment.Top),
                ) {
                    TranscriptDrawer(
                        content = drawer,
                        width = transcriptWidth.dp,
                        textColor = if (isMine) Color.White else colors.textPrimary,
                        metaColor = metaColor,
                        progressTint = if (isMine) Color.White else colors.accent,
                        streams = hasAppeared && revealsArrival && !reduceMotion,
                        reservation = if (metaInTranscript) MetaSpec(parts.time, showsReceipt = isMine) else null,
                        install = installState.value,
                        installActive = installActive,
                    )
                }
                if (showsReactions) {
                    BubbleReactionFoot(parts, Modifier.width(contentWidth.dp).padding(top = 7.dp), meta)
                }
            }
            if (metaInTranscript && drawer != null) {
                Box(Modifier.align(Alignment.BottomEnd).padding(end = VoiceBubbleMath.TRANSCRIPT_INSET.dp)) { meta() }
            }
        }
    }
    if (parts.embedded) {
        Box(modifier.fillMaxWidth(), contentAlignment = if (isMine) Alignment.BottomEnd else Alignment.BottomStart) {
            Box(Modifier.widthIn(max = parts.rowWidth - VOICE_GUTTER)) { bubble() }
        }
    } else {
        Box(modifier) { bubble() }
    }
}

/** The empty strip on the opposite side of a voice row (`VoiceMessageBubble.swift:193-198`). */
private val VOICE_GUTTER = 48.dp
private val REPLY_FONT = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)

private enum class PlayGlyph { Loading, Retry, Play, Pause }

/**
 * The 38 dp play disc (`VoiceMessageBubble.swift:329-362`): a small spinner while the audio is fetched,
 * a retry arrow when that failed, else play / pause, morphing with a slight upward slide. Shrinks to 0.88
 * when pressed; hidden from TalkBack (the bubble's default action plays).
 */
@Composable
private fun PlayButton(isMine: Boolean, glyph: PlayGlyph, enabled: Boolean, onClick: () -> Unit) {
    val fill = if (isMine) Color.White.copy(alpha = 0.22f) else ShroudTheme.colors.accent
    Box(
        Modifier
            .size(VoiceBubbleMath.PLAY_BUTTON_SIZE.dp)
            .pressable(enabled = enabled, scale = 0.88f, dimming = 0f, haptic = Haptic.None, onClick = onClick)
            .clearAndSetSemantics {}
            .clip(CircleShape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = glyph,
            transitionSpec = {
                val spec = Motion.snappy<Float>()
                (fadeIn(spec) + slideInVertically(Motion.snappy()) { it / 4 }) togetherWith
                    (fadeOut(spec) + slideOutVertically(Motion.snappy()) { -it / 4 })
            },
            contentAlignment = Alignment.Center,
            label = "voicePlay",
        ) { shown ->
            when (shown) {
                PlayGlyph.Loading -> Spinner(Color.White, size = 16.dp)
                PlayGlyph.Retry -> ShroudIcon(ShroudIcons.ArrowClockwiseBold, Color.White, size = 16.dp)
                // `play.fill` is left-heavy: nudged onto the optical centre (`:351-352`).
                PlayGlyph.Play -> ShroudIcon(ShroudIcons.PlayFill, Color.White, size = 17.dp, modifier = Modifier.offset(x = 1.dp))
                PlayGlyph.Pause -> ShroudIcon(ShroudIcons.PauseFill, Color.White, size = 17.dp)
            }
        }
    }
}

/**
 * The waveform, 26 dp tall with a full-height target (`VoiceMessageBubble.swift:364-399`). Only the
 * loaded note scrubs: a drag from touch-down moves the playhead live, the release seeks. Any other note
 * leaves the drag to swipe-to-reply and the list (nothing is consumed).
 */
@Composable
private fun ScrubbableWaveform(
    samples: List<Float>,
    progress: Double,
    played: Color,
    remaining: Color,
    width: Dp,
    enabled: Boolean,
    onScrub: (Double) -> Unit,
    onSeek: (Double) -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scrub by rememberUpdatedState(onScrub)
    val seek by rememberUpdatedState(onSeek)
    val gesture = if (enabled) {
        Modifier.pointerInput(rtl) {
            fun fraction(x: Float): Double {
                val w = size.width.toFloat()
                if (w <= 0f) return 0.0
                val f = (x / w).toDouble().coerceIn(0.0, 1.0)
                return if (rtl) 1 - f else f
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                var last = down.position.x
                scrub(fraction(last))
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.positionChange() != Offset.Zero) change.consume()
                    last = change.position.x
                    if (!change.pressed) {
                        change.consume()
                        seek(fraction(last))
                        return@awaitEachGesture
                    }
                    scrub(fraction(last))
                }
                seek(fraction(last))
            }
        }
    } else {
        Modifier
    }
    VoiceWaveformView(
        samples = samples,
        progress = progress.toFloat(),
        playedColor = played,
        remainingColor = remaining,
        modifier = Modifier.width(width).height(26.dp).then(gesture),
    )
}

/**
 * The footer under the waveform (`VoiceMessageBubble.swift:403-435`): the elapsed or full time, the
 * unplayed dot, the speed chip while this note is loaded, and — on a bare note — the time and ticks.
 */
@Composable
private fun VoiceFooter(
    time: String,
    metaColor: Color,
    showsUnplayedDot: Boolean,
    speed: String?,
    isMine: Boolean,
    onSpeed: () -> Unit,
    meta: (@Composable () -> Unit)?,
) {
    val colors = ShroudTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(time, style = inter(11f, FontWeight.Medium, tabularDigits = true).copy(color = metaColor), maxLines = 1, softWrap = false)
        AnimatedVisibility(showsUnplayedDot, enter = Motion.iconSwap.enter, exit = Motion.iconSwap.exit) {
            Canvas(Modifier.size(5.dp)) { drawCircle(colors.accent) }
        }
        AnimatedVisibility(speed != null, enter = Motion.iconSwap.enter, exit = Motion.iconSwap.exit) {
            SpeedChip(speed.orEmpty(), isMine, onSpeed)
        }
        Spacer(Modifier.weight(1f).widthIn(min = 4.dp))
        meta?.invoke()
    }
}

/**
 * Telegram's 1× / 1.5× / 2× chip (`VoiceMessageBubble.swift:479-507`): 10 sp bold, tabular; a bigger
 * target than the 16 dp chip without moving the footer; shrinks to 0.85, no haptic of its own.
 */
@Composable
private fun SpeedChip(label: String, isMine: Boolean, onClick: () -> Unit) {
    val colors = ShroudTheme.colors
    Box(
        Modifier
            .overhangHorizontal(SPEED_TOUCH_OUTSET)
            .pressable(scale = 0.85f, dimming = 0f, haptic = Haptic.None, onClick = onClick)
            .clearAndSetSemantics {}
            .padding(horizontal = SPEED_TOUCH_OUTSET, vertical = 0.dp),
    ) {
        BasicText(
            label,
            style = inter(10f, FontWeight.Bold, tabularDigits = true).copy(color = if (isMine) Color.White else colors.accentText),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .clip(CircleShape)
                .background(if (isMine) Color.White.copy(alpha = 0.22f) else colors.accentSoft)
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

private val SPEED_TOUCH_OUTSET = 8.dp

/** Like [overhang], sideways: the chip's touch padding does not move the footer. */
private fun Modifier.overhangHorizontal(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val px = horizontal.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = 2 * px))
    layout((placeable.width - 2 * px).coerceAtLeast(0), placeable.height) { placeable.place(-px, 0) }
}

/**
 * The unfolded transcript (`VoiceMessageBubble.swift:584-660`): the text (streaming when revealed in
 * front of the reader), "Transcribing…" / the model download with a bar, or "No speech detected". With
 * the time and ticks overlaid on its last line, the content keeps clear of them.
 */
@Composable
private fun TranscriptDrawer(
    content: DrawerContent?,
    width: Dp,
    textColor: Color,
    metaColor: Color,
    progressTint: Color,
    streams: Boolean,
    reservation: MetaSpec?,
    install: TranscriptionInstallState,
    installActive: Boolean,
) {
    val clearance = metaClearance(reservation)
    Box(Modifier.padding(start = VoiceBubbleMath.TRANSCRIPT_INSET.dp, end = VoiceBubbleMath.TRANSCRIPT_INSET.dp, top = 8.dp, bottom = 1.dp).width(width)) {
        when (content) {
            is DrawerContent.Text -> VoiceTranscriptText(content.text, textColor, streams, reservation, Modifier.fillMaxWidth())
            DrawerContent.Working -> {
                val downloading = installActive && install.phase == TranscriptionInstallState.Phase.Downloading
                val text = VoiceBubbleMath.progressLabel(downloading, install.fractionCompleted, install.isDeterminate, install.languageName)
                Column(Modifier.padding(end = clearance), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    BasicText(text, style = inter(13f, FontWeight.Medium).copy(color = metaColor), modifier = Modifier.shimmering())
                    if (downloading && install.isDeterminate) {
                        LinearProgress(max(install.fractionCompleted, 0.02).toFloat(), progressTint)
                    }
                }
            }
            DrawerContent.NoSpeech -> BasicText(
                "No speech detected",
                style = inter(13f).copy(color = metaColor, fontStyle = FontStyle.Italic),
                modifier = Modifier.padding(end = clearance),
            )
            null -> Unit
        }
    }
}

/** Room kept beside the progress or "no speech" line for the time and ticks: meta width + 8 (`:622-626`). */
@Composable
private fun metaClearance(reservation: MetaSpec?): Dp {
    if (reservation == null) return 0.dp
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val timeWidth = remember(reservation.time, measurer) {
        measurer.measure(reservation.time, MessageBubbleMetrics.metaStyle, softWrap = false).size.width
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val time = with(density) { ceil(timeWidth.toFloat()).toDp() }
    return MessageBubbleMetrics.metaWidth(time.value, reservation.showsReceipt).dp + MessageBubbleMetrics.metaGap
}

/** iOS's linear `ProgressView`: a 4 dp track with the tint's fill (`:636-640`). */
@Composable
private fun LinearProgress(fraction: Float, tint: Color) {
    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
        val y = size.height / 2f
        drawLine(tint.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), strokeWidth = size.height, cap = StrokeCap.Round)
        drawLine(tint, Offset(0f, y), Offset(size.width * fraction.coerceIn(0f, 1f), y), strokeWidth = size.height, cap = StrokeCap.Round)
    }
}
