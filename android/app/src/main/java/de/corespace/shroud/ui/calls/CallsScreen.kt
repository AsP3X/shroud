package de.corespace.shroud.ui.calls

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.corespace.shroud.core.calls.CallHistory
import de.corespace.shroud.core.calls.CallRun
import de.corespace.shroud.core.calls.RecentCall
import de.corespace.shroud.core.messaging.ChatListFormatting
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.CallModality
import de.corespace.shroud.ui.components.EmptyState
import de.corespace.shroud.ui.components.GlassBarMetrics
import de.corespace.shroud.ui.components.GlassBarRow
import de.corespace.shroud.ui.components.GlassBarTitle
import de.corespace.shroud.ui.components.ListEntranceHost
import de.corespace.shroud.ui.components.ListLoadError
import de.corespace.shroud.ui.components.LocalGlassBackdrop
import de.corespace.shroud.ui.components.MainScrollBackdrop
import de.corespace.shroud.ui.components.NameAvatar
import de.corespace.shroud.ui.components.PullToRefresh
import de.corespace.shroud.ui.components.ScrollEdgeEffect
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.SkeletonChatList
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.edgeEffectSource
import de.corespace.shroud.ui.components.entranceRow
import de.corespace.shroud.ui.components.highlightRow
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.rememberGlassBackdrop
import de.corespace.shroud.ui.components.rememberToastState
import de.corespace.shroud.ui.shell.LocalTabBarClearance
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.perform
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The Calls tab: the account's call history, newest first; calls in a row with the same person
 * within an hour share one section (iOS `CallsView`, `ios/shroud/Features/Main/CallsView.swift`;
 * calls §9; design `qAdG9`, `uof7e`, `xiRKA`, `w4INX`, `tuec2`).
 *
 * States ([CallsTabRules.content]): the skeleton on the first load, "Can't load calls" when that
 * failed, "No calls yet", or the runs; cross-fading (`Motion.fade`). Every appearance and a pull
 * reload the newest page; the last run on screen asks for the next older page, and a failed older
 * page leaves "Load older calls" at the end. A call button places the call; if it cannot start,
 * the reason shows as a toast (`CallController.lastError`, as the conversation does, calls §10).
 *
 * Entry-point signature frozen by plan §1.7.13.
 */
@Composable
fun CallsScreen() {
    val ports = rememberCallPorts()
    val history by ports.history.collectAsState()
    val scope = rememberCoroutineScope()
    val toast = rememberToastState()
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val content = CallsTabRules.content(history)
    val runs = remember(history.recent) { CallHistory.runs(history.recent) }
    val view = LocalView.current

    // Calls of our other devices, or from while the socket was down, show on every visit (:106-112).
    LifecycleResumeEffect(ports) {
        val job = scope.launch { ports.refreshHistory() }
        onPauseOrDispose { job.cancel() }
    }
    val call: (RecentCall, CallModality) -> Unit = { item, modality ->
        scope.launch {
            ports.startCall(item.peerUserId, item.peerUsername, modality)
            ports.lastError.value?.let { toast.show(Toast.failure(it)) }
        }
    }
    val formats = rememberCallTimeFormats()

    Box(Modifier.fillMaxSize()) {
        // The entrance replays when rows replace an empty list (`listEntranceHost(resetOn:)`, :117).
        ListEntranceHost(key = runs.isEmpty()) {
        CallsScaffold(onRefresh = { ports.refreshHistory() }) {
            when (content) {
                CallsTabContent.Skeleton -> item(key = "skeleton", contentType = "state") {
                    SkeletonChatList(modifier = Modifier.animateItem(Motion.fade(), null, Motion.fade()))
                }
                CallsTabContent.Error -> item(key = "error", contentType = "state") {
                    ListLoadError(
                        title = CallsTabRules.ERROR_TITLE,
                        message = history.error.orEmpty(),
                        onRetry = { ports.refreshHistory() },
                        modifier = Modifier.animateItem(Motion.fade(), null, Motion.fade()),
                    )
                }
                CallsTabContent.Empty -> item(key = "empty", contentType = "state") {
                    EmptyState(
                        icon = ShroudIcons.PhoneFill,
                        title = CallsTabRules.EMPTY_TITLE,
                        message = CallsTabRules.EMPTY_MESSAGE,
                        modifier = Modifier.animateItem(Motion.fade(), null, Motion.fade()),
                    )
                }
                CallsTabContent.List -> {
                    itemsIndexed(runs, key = { _, run -> run.id.toString() }, contentType = { _, run -> if (run.calls.size == 1) "call" else "run" }) { index, run ->
                        val key = run.id.toString()
                        Column(
                            Modifier
                                .animateItem(Motion.fade(), Motion.standard(), Motion.fade())
                                .entranceRow(index),
                        ) {
                            if (run.calls.size == 1) {
                                RecentRow(run.latest, formats, onCall = call)
                            } else {
                                RunSection(
                                    run = run,
                                    expanded = key in expanded,
                                    formats = formats,
                                    onToggle = {
                                        view.perform(Haptic.Light)
                                        expanded = if (key in expanded) expanded - key else expanded + key
                                    },
                                    onCall = call,
                                )
                            }
                            Separator()
                        }
                        // The last row asks for the next older page (:74-79).
                        if (index == runs.lastIndex) {
                            LaunchedEffect(run.id) { ports.loadOlderHistory() }
                        }
                    }
                    item(key = "footer", contentType = "footer") {
                        Footer(CallsTabRules.footer(history), onLoadOlder = { scope.launch { ports.loadOlderHistory() } })
                    }
                }
            }
        }
        }
        ToastHost(toast, bottomInset = LocalTabBarClearance.current)
    }
}

/** The clock and calendar the rows format with, read once per configuration. */
internal data class CallTimeFormats(val zone: ZoneId, val locale: Locale, val is24h: Boolean, val dayMonth: String) {
    fun timeLabel(at: Instant): String = ChatListFormatting.timeLabel(at, Instant.now(), zone, locale, is24h)

    fun runTimeLabel(at: Instant): String = CallsTabRules.runTimeLabel(at, Instant.now(), zone, locale, is24h, dayMonth)

    fun label(call: RecentCall, named: Boolean): String = CallsTabRules.accessibilityLabel(call, named, zone, locale, is24h)
}

@Composable
private fun rememberCallTimeFormats(): CallTimeFormats {
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val is24h = DateFormat.is24HourFormat(context)
    return remember(locale, is24h) {
        CallTimeFormats(ZoneId.systemDefault(), locale, is24h, DateFormat.getBestDateTimePattern(locale, "dMMM"))
    }
}

/**
 * The Calls tab's chrome: the root bar ("Calls", no buttons, no search) over one list that pulls
 * to refresh, the scroll edge effect under the bar (`MainScrollScreen(title: "Calls")`,
 * `CallsView.swift:45-51`; shell-chats §7). The list ends above the floating tab bar.
 */
@Composable
private fun CallsScaffold(onRefresh: suspend () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    val backdrop = rememberGlassBackdrop()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barBlock = statusTop + GlassBarMetrics.mainBarHeight
    val clearance = LocalTabBarClearance.current
    val bottom = if (clearance > 0.dp) clearance else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scrolledUnder by remember(state) {
        derivedStateOf { MainScrollBackdrop.isUnderBar(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
    }
    Box(Modifier.fillMaxSize().background(ShroudTheme.colors.background)) {
        PullToRefresh(onRefresh = onRefresh, indicatorTop = barBlock) {
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize().edgeEffectSource(backdrop),
                contentPadding = PaddingValues(top = barBlock, bottom = bottom),
            ) {
                content()
            }
        }
        Box(Modifier.fillMaxWidth()) {
            ScrollEdgeEffect(visible = scrolledUnder, extent = barBlock, backdrop = backdrop)
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    GlassBarRow(
                        title = { GlassBarTitle(CallsTabRules.TITLE) },
                        modifier = Modifier.padding(top = GlassBarMetrics.topPadding),
                    )
                }
            }
        }
    }
}

/** The 1 dp separator under a run, inset 76 (:80-83). */
@Composable
private fun Separator() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 76.dp)
            .height(1.dp)
            .background(ShroudTheme.colors.separator),
    )
}

/** The end of the list: a spinner while an older page loads, or "Load older calls" after it failed; then 16 dp (:85-102). */
@Composable
private fun Footer(footer: CallsTabFooter, onLoadOlder: () -> Unit) {
    val colors = ShroudTheme.colors
    Column(Modifier.fillMaxWidth()) {
        when (footer) {
            CallsTabFooter.Loading -> Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    .clearAndSetSemantics { contentDescription = CallsTabRules.LOADING_OLDER },
                contentAlignment = Alignment.Center,
            ) {
                Spinner(colors.textSecondary)
            }
            CallsTabFooter.LoadOlder -> Box(
                Modifier
                    .padding(vertical = 6.dp)
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .pressable(scale = 0.96f, onClick = onLoadOlder),
                contentAlignment = Alignment.Center,
            ) {
                ShroudText(CallsTabRules.LOAD_OLDER, inter(15f, FontWeight.SemiBold), colors.accent, maxLines = 1)
            }
            CallsTabFooter.None -> Unit
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** One call (`recentRow`, :300-340): avatar 48, name, direction and how it went, then the time over the two call buttons. */
@Composable
private fun RecentRow(item: RecentCall, formats: CallTimeFormats, onCall: (RecentCall, CallModality) -> Unit) {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NameAvatar(item.peerUsername, size = 48.dp, fontSize = 17.sp)
        // One TalkBack stop with the whole date and the duration in words; the buttons stay their own.
        Column(
            Modifier
                .weight(1f)
                .clearAndSetSemantics { contentDescription = formats.label(item, named = true) },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ShroudText(item.peerUsername, inter(16f, FontWeight.SemiBold), colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                DirectionArrow(item, colors.textSecondary)
                StatusText(item, size = 13f, color = colors.textSecondary)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShroudText(formats.timeLabel(item.at), inter(12f), colors.textSecondary, Modifier.clearAndSetSemantics {}, maxLines = 1)
            if (!item.peerDeleted) CallButtons(item, onCall)
        }
    }
}

/**
 * A run of several calls (`runView`, `runHeader`, :143-239): the header — who, "3 calls · 1
 * missed", the latest time and a chevron — toggles the calls under it, which slide down from
 * under the header (fade only under reduce motion), threaded to the avatar.
 */
@Composable
private fun RunSection(
    run: CallRun,
    expanded: Boolean,
    formats: CallTimeFormats,
    onToggle: () -> Unit,
    onCall: (RecentCall, CallModality) -> Unit,
) {
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    val chevron by animateFloatAsState(if (expanded) -180f else 0f, Motion.respecting(reduceMotion, Motion.standard()), label = "runChevron")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .highlightRow(onClick = onToggle, haptic = Haptic.None)
                .clearAndSetSemantics {
                    contentDescription = CallsTabRules.runHeaderLabel(run)
                    stateDescription = CallsTabRules.expandedValue(expanded)
                    role = Role.Button
                    onClick(label = CallsTabRules.expandAction(expanded)) {
                        onToggle()
                        true
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NameAvatar(run.latest.peerUsername, size = 48.dp, fontSize = 17.sp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ShroudText(run.latest.peerUsername, inter(16f, FontWeight.SemiBold), colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val (count, missed) = CallsTabRules.runSummary(run)
                val summary = buildAnnotatedString {
                    append(count)
                    if (missed != null) withStyle(SpanStyle(color = colors.danger)) { append(missed) }
                }
                BasicText(summary, style = inter(13f).copy(color = colors.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShroudText(formats.timeLabel(run.latest.at), inter(12f), colors.textSecondary, maxLines = 1)
                Box(
                    Modifier
                        .size(32.dp)
                        .background(colors.backgroundGrouped, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    ShroudIcon(ShroudIcons.CaretDownBold, colors.textSecondary, Modifier.graphicsLayer { rotationZ = chevron }, size = 14.dp)
                }
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduceMotion) fadeIn(Motion.reduced()) else expandVertically(Motion.standard(), expandFrom = Alignment.Top) + fadeIn(Motion.standard()),
            exit = if (reduceMotion) fadeOut(Motion.reduced()) else shrinkVertically(Motion.standard(), shrinkTowards = Alignment.Top) + fadeOut(Motion.standard()),
        ) {
            val thread = colors.separator
            Column(
                Modifier
                    .fillMaxWidth()
                    // A thread down from the avatar ties the calls to the person above them (:166-172).
                    .drawBehind {
                        val width = 2.dp.toPx()
                        val bottomGap = 10.dp.toPx()
                        val height = size.height - bottomGap
                        if (height > 0f) {
                            drawRoundRect(thread, Offset(39.dp.toPx(), 0f), Size(width, height), CornerRadius(width / 2f, width / 2f))
                        }
                    },
            ) {
                for (item in run.calls) {
                    Separator()
                    RunCallRow(item, formats, onCall)
                }
            }
        }
    }
}

/** One call inside a section (`runCallRow`, :254-298): how it went and when, and the call buttons. */
@Composable
private fun RunCallRow(item: RecentCall, formats: CallTimeFormats, onCall: (RecentCall, CallModality) -> Unit) {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 76.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clearAndSetSemantics { contentDescription = formats.label(item, named = false) },
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                DirectionArrow(item, colors.textSecondary)
                StatusText(item, size = 14f, color = colors.textPrimary)
            }
            ShroudText(formats.runTimeLabel(item.at), inter(12f), colors.textSecondary, maxLines = 1)
        }
        if (!item.peerDeleted) CallButtons(item, onCall)
    }
}

@Composable
private fun DirectionArrow(item: RecentCall, tint: androidx.compose.ui.graphics.Color) {
    ShroudIcon(if (item.isOutgoing) ShroudIcons.ArrowUpRightBold else ShroudIcons.ArrowDownLeftBold, tint, size = 12.dp)
}

/** "Outgoing voice · 4:12"; a missed call's outcome in red; one line that may shrink to 85 % (:403-411). */
@Composable
private fun StatusText(item: RecentCall, size: Float, color: androidx.compose.ui.graphics.Color) {
    val danger = ShroudTheme.colors.danger
    val text = buildAnnotatedString {
        append(CallsTabRules.statusPrefix(item))
        if (CallHistory.isMissed(item)) {
            withStyle(SpanStyle(color = danger)) { append(CallHistory.outcome(item)) }
        } else {
            append(CallHistory.outcome(item))
        }
    }
    BasicText(
        text = text,
        style = inter(size).copy(color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = (size * 0.85f).sp, maxFontSize = size.sp, stepSize = 0.25.sp),
    )
}

/** Voice and video (`callButtons`, :358-402): 32 dp discs, 44 dp to touch, far enough apart that the targets never overlap. */
@Composable
private fun CallButtons(item: RecentCall, onCall: (RecentCall, CallModality) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CallBackButton(ShroudIcons.PhoneFill, CallsTabRules.callLabel(item.peerUsername)) { onCall(item, CallModality.Voice) }
        CallBackButton(ShroudIcons.VideoCameraFill, CallsTabRules.videoCallLabel(item.peerUsername)) { onCall(item, CallModality.Video) }
    }
}

@Composable
private fun CallBackButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = ShroudTheme.colors
    val reduceMotion = ShroudTheme.reduceMotion
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val view = LocalView.current
    LaunchedEffect(pressed) {
        if (pressed) view.perform(Haptic.Medium)
    }
    val scale by animateFloatAsState(if (pressed && !reduceMotion) 0.86f else 1f, if (pressed) Motion.press() else Motion.release(), label = "callBackPress")
    Box(
        Modifier
            .size(32.dp)
            .layout { measurable, _ ->
                // A 44 dp target round the 32 dp disc, with no change to the row (:375-376).
                val reach = 6.dp.roundToPx()
                val side = 32.dp.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(side + reach * 2, side + reach * 2))
                layout(side, side) { placeable.place(-reach, -reach) }
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick {
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(colors.backgroundGrouped, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            ShroudIcon(icon, colors.accent, size = 16.dp)
        }
    }
}
