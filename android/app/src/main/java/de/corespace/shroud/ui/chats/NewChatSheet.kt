package de.corespace.shroud.ui.chats

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.corespace.shroud.core.messaging.ChatListFormatting
import de.corespace.shroud.core.model.AppClock
import de.corespace.shroud.ui.components.GlassBarMetrics
import de.corespace.shroud.ui.components.GlassBarRow
import de.corespace.shroud.ui.components.GlassBarTitle
import de.corespace.shroud.ui.components.GlassStyle
import de.corespace.shroud.ui.components.ListLoadError
import de.corespace.shroud.ui.components.LocalGlassBackdrop
import de.corespace.shroud.ui.components.NameAvatar
import de.corespace.shroud.ui.components.NoLearningTextInput
import de.corespace.shroud.ui.components.SearchKeyboard
import de.corespace.shroud.ui.components.SheetStyle
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudSheet
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.SkeletonChatList
import de.corespace.shroud.ui.components.glassBackdropSource
import de.corespace.shroud.ui.components.glassSurface
import de.corespace.shroud.ui.components.highlightRow
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.rememberGlassBackdrop
import de.corespace.shroud.ui.shell.LocalTabBarClearance
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** New Chat's words (`NewChatSheet.swift`). */
object NewChatCopy {
    const val TITLE = "New Chat"
    const val CANCEL = "Cancel"
    const val SEARCH_PROMPT = "Search contacts"
    const val CLEAR_SEARCH = "Clear search"
}

/**
 * New Chat: pick a contact to start or open a conversation (`NewChatSheet.swift`; shell-chats §9;
 * design `New Chat` WHZDi). Opened from the Chats bar and the empty state.
 *
 * Human: A large sheet slides up over a light dim — "Cancel" in a glass capsule and the centred
 * title on top, the contacts below (name, and "online" in the accent colour or "last seen …"), and
 * the "Search contacts" field floating at the bottom, riding the keyboard. Until the roster's first
 * load answers it shows placeholder rows; a failed load says "Can't load contacts" with Try Again;
 * an empty roster says "No contacts"; a search without hits says "No Results for “…”". Picking a
 * contact closes the sheet and opens the chat. Back, Cancel, a tap on the dim or a drag down close it.
 *
 * Agent: [onSelect] runs first, then [onDismiss] (NCS:47-50). The search text lives with the sheet
 * and starts empty each time (iOS `@State` in the sheet). On appearing with an empty roster it
 * asks for one (`refreshContacts()`, NCS:99-103). The sheet resets the tab-bar clearance (§9.1)
 * and blurs its own list behind the glass controls. No `rememberSaveable`: nothing here outlives
 * the sheet.
 */
@Composable
internal fun NewChatSheet(
    visible: Boolean,
    source: ChatsSource,
    clock: AppClock,
    onDismiss: () -> Unit,
    onSelect: (UUID, String) -> Unit,
) {
    val colors = ShroudTheme.colors
    ShroudSheet(
        visible = visible,
        onDismiss = onDismiss,
        style = SheetStyle.Full,
        paneTitle = NewChatCopy.TITLE,
        showsHandle = false,
        cornerRadius = 38.dp,
        scrim = colors.sheetScrim,
    ) {
        CompositionLocalProvider(LocalTabBarClearance provides 0.dp) {
            NewChatBody(source, clock, onDismiss, onSelect)
        }
    }
}

@Composable
private fun NewChatBody(source: ChatsSource, clock: AppClock, onDismiss: () -> Unit, onSelect: (UUID, String) -> Unit) {
    val context = LocalContext.current
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val is24h = DateFormat.is24HourFormat(context)
    val zone = ZoneId.systemDefault()
    var query by remember { mutableStateOf("") }
    var snapshot by remember(source) { mutableStateOf(source.contactsSnapshot()) }
    LaunchedEffect(source) { source.contactChanges.collect { snapshot = source.contactsSnapshot() } }
    LaunchedEffect(source) { if (source.contactsSnapshot().contacts.isEmpty()) source.refreshContacts(force = false) }
    val content = remember(snapshot, query, locale, is24h, zone) {
        val now = clock.now()
        NewChatState.derive(snapshot, query, locale) { presence -> ChatListFormatting.presenceLabel(presence, now, zone, locale, is24h) }
    }
    val backdrop = rememberGlassBackdrop()
    var searchHeight by remember { mutableIntStateOf(0) }
    val searchHeightDp = with(LocalDensity.current) { searchHeight.toDp() }

    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        Column(Modifier.fillMaxSize().padding(top = 14.dp)) {
            GlassBarRow(
                leading = { CancelCapsule(onDismiss) },
                title = { GlassBarTitle(NewChatCopy.TITLE) },
                sideReserve = CANCEL_MIN_WIDTH + GlassBarMetrics.spacing,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize().glassBackdropSource()) {
                    NewChatContentView(
                        content = content,
                        bottomPadding = searchHeightDp,
                        onRetry = { source.refreshContacts(force = true) },
                        onSelect = { row ->
                            onSelect(row.userId, row.username)
                            onDismiss()
                        },
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { searchHeight = it.height }
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
                        .navigationBarsPadding(),
                ) {
                    ContactSearchField(query, onQueryChange = { query = it })
                }
            }
        }
    }
}

/**
 * The body in iOS's order (`NewChatSheet.swift:22-86`): placeholders and the error top-aligned, the
 * unavailable views centred, or the rows. Changes of state fade; filtering moves rows with
 * `Motion.standard` (NCS:87-90).
 */
@Composable
private fun NewChatContentView(
    content: NewChatContent,
    bottomPadding: Dp,
    onRetry: suspend () -> Unit,
    onSelect: (NewChatRow) -> Unit,
) {
    AnimatedContent(
        targetState = content,
        contentKey = { it::class },
        transitionSpec = { (fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())).using(SizeTransform(clip = false)) },
        modifier = Modifier.fillMaxSize(),
        label = "newChatContent",
    ) { shown ->
        when (shown) {
            NewChatContent.Loading -> Box(Modifier.fillMaxSize()) { SkeletonChatList(rows = 6) }
            is NewChatContent.LoadError -> Box(Modifier.fillMaxSize()) {
                ListLoadError(NewChatState.LOAD_ERROR_TITLE, shown.message, onRetry)
            }
            NewChatContent.NoContacts -> UnavailableView(
                ShroudIcons.Users,
                NewChatState.NO_CONTACTS_TITLE,
                NewChatState.NO_CONTACTS_MESSAGE,
                bottomPadding,
            )
            is NewChatContent.NoResults -> UnavailableView(
                ShroudIcons.MagnifyingGlass,
                NewChatState.noResultsTitle(shown.query),
                NewChatState.NO_RESULTS_MESSAGE,
                bottomPadding,
            )
            is NewChatContent.Rows -> ContactRows(shown.rows, bottomPadding, onSelect)
        }
    }
}

@Composable
private fun ContactRows(rows: List<NewChatRow>, bottomPadding: Dp, onSelect: (NewChatRow) -> Unit) {
    val reduceMotion = ShroudTheme.reduceMotion
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        itemsIndexed(rows, key = { _, row -> row.userId.toString() }) { index, row ->
            Column(
                Modifier.animateItem(
                    fadeInSpec = Motion.fade(),
                    placementSpec = Motion.respecting(reduceMotion, Motion.standard()),
                    fadeOutSpec = Motion.fade(),
                ),
            ) {
                ContactRow(row, onClick = { onSelect(row) })
                if (index < rows.lastIndex) ListSeparator(inset = 68.dp)
            }
        }
    }
}

/**
 * One contact (`NewChatSheet.swift:46-84`; shell-chats §9.3, design `Contact …`): 40 dp avatar
 * (initials 15 sp), gap 12; name 16 SemiBold over the status 13 (`accent` when online), 1 dp apart;
 * padding h 16 v 10 inside the press area (60 dp). The press fill is `rowPressed` — the default
 * `backgroundGrouped` equals the sheet's dark row colour and would not show (NCS:77-81).
 */
@Composable
private fun ContactRow(row: NewChatRow, onClick: () -> Unit) {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .highlightRow(onClick = onClick, fill = colors.rowPressed)
            .clearAndSetSemantics {
                contentDescription = "${row.username}, ${row.status}"
                role = Role.Button
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NameAvatar(row.username, seed = row.avatarSeed, size = 40.dp, fontSize = 15.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            ShroudText(row.username, inter(16f, FontWeight.SemiBold), colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ShroudText(row.status, inter(13f), if (row.online) colors.accent else colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * iOS `ContentUnavailableView` (NCS:35-44): a 48 dp glyph in `textSecondary`, the title 20 sp Bold,
 * the description 15 sp `textSecondary`, centred in the space above the search field.
 */
@Composable
private fun UnavailableView(icon: ImageVector, title: String, message: String, bottomPadding: Dp) {
    val colors = ShroudTheme.colors
    Box(Modifier.fillMaxSize().padding(bottom = bottomPadding), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ShroudIcon(icon, colors.textSecondary, Modifier.padding(bottom = 4.dp), size = 48.dp)
            ShroudText(title, inter(20f, FontWeight.Bold), colors.textPrimary, Modifier.semantics { heading() }, textAlign = TextAlign.Center)
            ShroudText(message, inter(15f), colors.textSecondary, textAlign = TextAlign.Center)
        }
    }
}

/** "Cancel" in a floating-glass capsule, 88 × 44, 17 sp `textPrimary` (design WHZDi). */
@Composable
private fun CancelCapsule(onClick: () -> Unit) {
    Box(
        Modifier
            .pressable(scale = 0.94f, onClick = onClick)
            .heightIn(min = GlassBarMetrics.controlSize)
            .widthIn(min = CANCEL_MIN_WIDTH)
            .glassSurface(CircleShape, GlassStyle.Soft)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        ShroudText(NewChatCopy.CANCEL, inter(17f), ShroudTheme.colors.textPrimary, maxLines = 1)
    }
}

/**
 * The search field at the bottom of the sheet (iOS 26 `.searchable` in a sheet, NCS:91; design
 * WHZDi): a 48 dp floating-glass capsule, padding h 16, gap 10; Phosphor `magnifying-glass-bold`
 * 18 dp `textPrimary`, "Search contacts" 17 sp; the clear button of the tab bar's field
 * (`x-circle-fill` 18 in a 28 box, 44 to the finger, "Clear search"). Search keyboard without
 * personalised learning (plan P5).
 */
@Composable
private fun ContactSearchField(query: String, onQueryChange: (String) -> Unit) {
    val colors = ShroudTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .glassSurface(CircleShape, GlassStyle.Soft)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudIcon(ShroudIcons.MagnifyingGlassBold, colors.textPrimary, size = 18.dp)
        Box(Modifier.weight(1f)) {
            NoLearningTextInput {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .semantics { contentDescription = NewChatCopy.SEARCH_PROMPT },
                    textStyle = inter(17f).copy(color = colors.textPrimary),
                    singleLine = true,
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = SearchKeyboard.options,
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) ShroudText(NewChatCopy.SEARCH_PROMPT, inter(17f), colors.textSecondary, maxLines = 1)
                            field()
                        }
                    },
                )
            }
        }
        AnimatedVisibility(
            visible = query.isNotEmpty(),
            enter = Motion.iconSwap(Motion.snappy()).enter,
            exit = Motion.iconSwap(Motion.snappy()).exit,
        ) {
            Box(
                Modifier
                    .touchArea(visible = 28, touch = 44)
                    .pressable(scale = 0.8f, onClick = { onQueryChange("") })
                    .semantics { contentDescription = NewChatCopy.CLEAR_SEARCH },
                contentAlignment = Alignment.Center,
            ) {
                ShroudIcon(ShroudIcons.XCircleFill, colors.textSecondary, size = 18.dp)
            }
        }
    }
}

/** Lays out [visible] dp square but takes touches over [touch] dp, centred (iOS `.contentShape(… inset(by: -8))`). */
private fun Modifier.touchArea(visible: Int, touch: Int): Modifier = layout { measurable, _ ->
    val touchPx = touch.dp.roundToPx()
    val visiblePx = visible.dp.roundToPx()
    val placeable = measurable.measure(Constraints.fixed(touchPx, touchPx))
    layout(visiblePx, visiblePx) { placeable.place((visiblePx - touchPx) / 2, (visiblePx - touchPx) / 2) }
}

/** The design's Cancel capsule is 88 dp wide. */
private val CANCEL_MIN_WIDTH = 88.dp
