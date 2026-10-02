package de.corespace.shroud.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.model.Ids
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.core.notifications.NotificationAuthorization
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.ActionSheet
import de.corespace.shroud.ui.components.ActionSheetItem
import de.corespace.shroud.ui.components.GlassBarTitle
import de.corespace.shroud.ui.components.IconTile
import de.corespace.shroud.ui.components.InsetDivider
import de.corespace.shroud.ui.components.ScrollEdgeEffect
import de.corespace.shroud.ui.components.SettingsCard
import de.corespace.shroud.ui.components.SettingsRow
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.edgeEffectSource
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.shell.LocalAppActions
import de.corespace.shroud.ui.shell.LocalShellNavigation
import de.corespace.shroud.ui.shell.LocalTabBarClearance
import de.corespace.shroud.ui.shell.SettingsRoute
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.TilePalette
import de.corespace.shroud.ui.theme.inter
import dev.chrisbanes.haze.rememberHazeState

/**
 * The Settings tab root (iOS `SettingsView`, `ios/shroud/Features/Main/SettingsView.swift:16-575`;
 * settings-lock §3; design `Settings` `PQtB1`, `Settings — Scrolled` `pt7Mu`, `· Dark` `nRZIR`,
 * `· 360` `JOwZ5`). [onOpenCalls] switches to the Calls tab (the Recent Calls row); null leaves
 * that row as "Soon" (`SettingsView.swift:28-29, 421-426`).
 *
 * Reads the session (name, handle, user id), the saved server, the colour theme and the
 * notification state from the container, loads the linked-device count once per appearance (best
 * effort: offline it shows no count, `SettingsView.swift:553-559`), and pushes its rows' screens on
 * the shell's Settings stack ([LocalShellNavigation]). Log Out confirms in an action sheet and then
 * runs the shell's wipe ([LocalAppActions]). The drawing is [SettingsRootContent].
 */
@Composable
fun SettingsScreen(onOpenCalls: (() -> Unit)?) {
    val container = LocalAppContainer.current
    val navigation = LocalShellNavigation.current
    val actions = LocalAppActions.current
    val context = LocalContext.current
    val session by container.auth.sessionController.session.collectAsState()
    val server by container.serverConfiguration.configuration.collectAsState()
    val theme by container.auth.colorTheme.theme.collectAsState()
    val notifications = container.notifications.controller
    val authorization by notifications.authorization.collectAsState()
    val notificationPrefs by notifications.preferences.state.collectAsState()
    val loggingOut by actions.isLoggingOut.collectAsState()
    val devices = container.auth.devices
    val devicesState by devices.state.collectAsState()
    // Core's list (K3): Devices' loads and removals show here at once (iOS `DevicesView(onCount:)`,
    // `SettingsView.swift:209-210`), and a failed reload keeps the rows, so the count stays what was shown.
    val deviceCount = devicesState.rows.size.takeIf { devicesState.hasLoaded }
    // Keyed on the signed-in account, never the token: UI reads no credentials (R3).
    val signedInUser = session?.userId
    LaunchedEffect(signedInUser) {
        if (signedInUser == null) return@LaunchedEffect
        // `loadDeviceCount` (`SettingsView.swift:553-559`): best effort, once per appearance.
        devices.refresh()
    }
    val state = SettingsRootState(
        username = session?.username,
        userId = session?.userId,
        deviceCount = deviceCount,
        notificationsSummary = SettingsCopy.notificationsSummary(authorization, notificationPrefs.enabled),
        themeTitle = theme.title,
        serverSubtitle = SettingsCopy.serverSubtitle(server),
        isLoggingOut = loggingOut,
        deviceNoun = DeviceNoun.current(context),
    )
    SettingsRootContent(
        state = state,
        onRoute = { route -> navigation.push(route) },
        onOpenCalls = onOpenCalls,
        onLogOut = { actions.logOut() },
    )
}

/**
 * What the Settings root shows; [SettingsScreen] builds it from the container.
 *
 * @property username the session's username; null signed out (the hero then says "Shroud User").
 * @property userId the session's user id, shown lower-case under "Signed in as".
 * @property deviceCount linked devices, null until the first load (the row shows no value).
 * @property deviceNoun "phone" or "tablet" ([DeviceNoun]), for the Log Out message.
 */
@Immutable
data class SettingsRootState(
    val username: String?,
    val userId: String?,
    val deviceCount: Int?,
    val notificationsSummary: String,
    val themeTitle: String,
    val serverSubtitle: String,
    val isLoggingOut: Boolean,
    val deviceNoun: String,
)

/** The Settings root's copy and value rules (settings-lock §3.4). Pure. */
object SettingsCopy {
    const val LOG_OUT = "Log Out"
    const val SIGNING_OUT = "Signing out…"

    /** TalkBack while signing out, without the ellipsis (`SettingsView.swift:550`). */
    const val SIGNING_OUT_SPOKEN = "Signing out"
    const val LOG_OUT_TITLE = "Log out of Shroud?"
    const val OFFICIAL_SERVER_SUBTITLE = "Official · api.shroud.app"

    /**
     * The Log Out confirmation's message (`SettingsView.swift:292-295`) with this device's noun
     * ([A]: iOS says "iPhone" / "iPad", Android "phone" / "tablet").
     */
    fun logOutMessage(deviceNoun: String): String =
        "Everything Shroud keeps on this $deviceNoun is deleted: messages, photos and voice notes, your encryption keys and " +
            "settings. Your other devices keep your chats. To sign in again you’ll need your password and encryption phrase."

    /**
     * The Notifications row's value (`SettingsView.swift:177-180`): "Off" when the system keeps
     * Shroud's notifications away or the Show Notifications switch is off, else "On".
     */
    fun notificationsSummary(authorization: NotificationAuthorization, enabled: Boolean): String =
        if (authorization == NotificationAuthorization.Denied || !enabled) "Off" else "On"

    /** The Server row's subtitle (`SettingsView.swift:182-189`). */
    fun serverSubtitle(configuration: ServerConfiguration): String = when (configuration.mode) {
        ServerConnectionMode.Official -> OFFICIAL_SERVER_SUBTITLE
        ServerConnectionMode.SelfHosted -> configuration.selfHostedPreview
    }

    /** The id under "Signed in as", lower-case like iOS's `uuidString.lowercased()` (`SettingsView.swift:394`). */
    fun userIdLine(userId: String): String = Ids.parse(userId)?.let(Ids::wire) ?: userId.lowercase()
}

/**
 * The Settings root's drawing (`SettingsView.swift:225-575`): the collapsing [SettingsHero] over
 * one scrolling column of cards on `backgroundGrouped`, and the compact glass bar the name settles
 * into. The cards start under a 134 dp spacer (the hero minus the bar, `SettingsView.swift:232-236`)
 * and end at the floating tab bar's top edge ([LocalTabBarClearance], `:251-252`). Content passing
 * under the bar is faded by the scroll edge effect only once the bar's title shows (memory: Liquid
 * Glass bar gotchas).
 */
@Composable
fun SettingsRootContent(
    state: SettingsRootState,
    onRoute: (SettingsRoute) -> Unit,
    onOpenCalls: (() -> Unit)?,
    onLogOut: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val colors = ShroudTheme.colors
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottom = maxOf(LocalTabBarClearance.current, navigationBottom)
    val displayName = SettingsIdentity.displayName(state.username)
    val handle = SettingsIdentity.handle(state.username)
    var showLogOutConfirm by rememberSaveable { mutableStateOf(false) }

    // The scroll offset the hero is drawn at, applied without animation and only past 0.2 dp
    // (`SettingsView.swift:263-273`).
    val heroOffset = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(scrollState, density) {
        snapshotFlow { scrollState.value / density.density }.collect { offset ->
            if (SettingsHeroMath.shouldApply(heroOffset.floatValue, offset)) heroOffset.floatValue = offset
        }
    }
    val frame = SettingsHeroMath.frame(heroOffset.floatValue)
    val backdrop = rememberHazeState()
    val barHeight = SettingsHeroMath.COMPACT_BAR_HEIGHT.dp

    Box(
        modifier
            .fillMaxSize()
            .background(colors.backgroundGrouped)
            .semantics { isTraversalGroup = true },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .edgeEffectSource(backdrop)
                .verticalScroll(scrollState)
                .padding(top = statusTop + barHeight),
        ) {
            Spacer(Modifier.height(SettingsHeroMath.contentSpacer.dp))
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ProfilePersonalizationCard()
                SignedInCard(handle, state.userId)
                PrimaryGroup(state, onRoute, onOpenCalls)
                SecondaryGroup(state, onRoute)
                LogOutButton(state.isLoggingOut) { showLogOutConfirm = true }
                Spacer(Modifier.height(16.dp))
            }
            Spacer(Modifier.height(bottom))
        }
        ScrollEdgeEffect(
            visible = heroOffset.floatValue > SettingsHeroMath.collapseDistance,
            extent = statusTop + barHeight,
            backdrop = backdrop,
        )
        // The compact title row: the name hands over to this real title at the very end of the
        // collapse; not hit-testable and hidden from TalkBack, the hero carries the label
        // (`SettingsView.swift:256-262`).
        Column(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Box(Modifier.fillMaxWidth().height(barHeight).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                GlassBarTitle(displayName, progress = frame.barTitleProgress)
            }
        }
        // Drawn after the cards, but it is the screen's header: read first (`SettingsView.swift:275-278`).
        SettingsHero(
            displayName,
            handle,
            frame,
            Modifier
                .padding(top = statusTop)
                .semantics { traversalIndex = -1f },
        )
    }

    ActionSheet(
        visible = showLogOutConfirm,
        title = SettingsCopy.LOG_OUT_TITLE,
        message = SettingsCopy.logOutMessage(state.deviceNoun),
        items = listOf(ActionSheetItem(SettingsCopy.LOG_OUT, destructive = true, onClick = onLogOut)),
        onDismiss = { showLogOutConfirm = false },
    )
}

/** Card 1, static, one TalkBack stop (`SettingsView.swift:353-376`). */
@Composable
private fun ProfilePersonalizationCard() {
    val colors = ShroudTheme.colors
    SettingsCard {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                ShroudIcon(ShroudIcons.IdentificationCardBold, colors.accent, size = 18.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ShroudText("Profile personalization", inter(16f), colors.textPrimary)
                ShroudText("Emoji status, colors, and photos come later.", inter(13f), colors.textSecondary)
            }
        }
    }
}

/**
 * Card 2: "Signed in as @username" and the user id, lower-case, monospace, selectable
 * (`SettingsView.swift:378-407`). One TalkBack stop.
 */
@Composable
private fun SignedInCard(handle: String, userId: String?) {
    val colors = ShroudTheme.colors
    SettingsCard {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(ShroudIcons.UserFill, TilePalette.coral)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ShroudText("Signed in as $handle", inter(16f), colors.textPrimary)
                if (userId != null) {
                    SelectionContainer {
                        ShroudText(SettingsCopy.userIdLine(userId), inter(11f, monospaced = true), colors.textSecondary)
                    }
                }
            }
        }
    }
}

/** Divider between the rows of a Settings card: inset 54 (`SettingsView.swift:561-566`). */
@Composable
private fun RowDivider() = InsetDivider(SettingsRowInset)

private val SettingsRowInset: Dp = 54.dp

/** Card 3 (`SettingsView.swift:409-443`). */
@Composable
private fun PrimaryGroup(state: SettingsRootState, onRoute: (SettingsRoute) -> Unit, onOpenCalls: (() -> Unit)?) {
    SettingsCard {
        // The notes chat, also pinned at the top of Chats.
        SettingsRow("Saved Messages", ShroudIcons.BookmarkSimpleFill, TilePalette.blue, onClick = { onRoute(SettingsRoute.SavedMessages) })
        RowDivider()
        SettingsRow("Recent Calls", ShroudIcons.PhoneFill, TilePalette.green, soon = onOpenCalls == null, onClick = onOpenCalls)
        RowDivider()
        SettingsRow(
            "Devices",
            ShroudIcons.MonitorSmartphone,
            TilePalette.orange,
            value = state.deviceCount?.toString(),
            onClick = { onRoute(SettingsRoute.Devices) },
        )
        RowDivider()
        SettingsRow("Chat Folders", ShroudIcons.FolderSimpleFill, TilePalette.cyan, soon = true, onClick = null)
    }
}

/** Card 4 (`SettingsView.swift:445-527`). */
@Composable
private fun SecondaryGroup(state: SettingsRootState, onRoute: (SettingsRoute) -> Unit) {
    val colors = ShroudTheme.colors
    SettingsCard {
        SettingsRow(
            "Notifications and Sounds",
            ShroudIcons.BellFill,
            TilePalette.pink,
            value = state.notificationsSummary,
            onClick = { onRoute(SettingsRoute.Notifications) },
        )
        RowDivider()
        SettingsRow("Privacy and Security", ShroudIcons.LockFill, colors.textSecondary, onClick = { onRoute(SettingsRoute.PrivacySecurity) })
        RowDivider()
        SettingsRow("Data and Storage", ShroudIcons.HardDriveFill, TilePalette.green, soon = true, onClick = null)
        RowDivider()
        SettingsRow("Appearance", ShroudIcons.PaletteFill, colors.accent, value = state.themeTitle, onClick = { onRoute(SettingsRoute.Appearance) })
        RowDivider()
        SettingsRow("Language", ShroudIcons.GlobeRegular, TilePalette.purple, soon = true, onClick = null)
        RowDivider()
        SettingsRow("Transcription", ShroudIcons.AudioLines, TilePalette.blue, onClick = { onRoute(SettingsRoute.Transcription) })
        RowDivider()
        // The Server row with its subtitle; TalkBack "Server, <subtitle>" (`SettingsView.swift:493-525`).
        SettingsRow("Server", ShroudIcons.HardDrivesFill, colors.accent, subtitle = state.serverSubtitle, onClick = { onRoute(SettingsRoute.Server) })
    }
}

/**
 * Card 5: Log Out (`SettingsView.swift:529-551`), disabled with a spinner and "Signing out…"
 * while the wipe runs.
 */
@Composable
private fun LogOutButton(isLoggingOut: Boolean, onTap: () -> Unit) {
    val colors = ShroudTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .pressable(enabled = !isLoggingOut, scale = 0.98f, dimming = 0.1f, haptic = Haptic.Medium, onClick = onTap)
            .clearAndSetSemantics { contentDescription = if (isLoggingOut) SettingsCopy.SIGNING_OUT_SPOKEN else SettingsCopy.LOG_OUT }
            .clip(RoundedCornerShape(14.dp))
            .background(colors.background)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isLoggingOut) Spinner(colors.danger, size = 18.dp)
            ShroudText(
                if (isLoggingOut) SettingsCopy.SIGNING_OUT else SettingsCopy.LOG_OUT,
                inter(16f, FontWeight.SemiBold),
                colors.danger,
            )
        }
    }
}

private val previewState = SettingsRootState(
    username = "noah_vorberg",
    userId = "3F2504E0-4F89-41D3-9A0C-0305E82C3301",
    deviceCount = 3,
    notificationsSummary = "On",
    themeTitle = "System",
    serverSubtitle = SettingsCopy.OFFICIAL_SERVER_SUBTITLE,
    isLoggingOut = false,
    deviceNoun = DeviceNoun.PHONE,
)

@Preview(name = "Settings · 412", widthDp = 412, heightDp = 917)
@Composable
private fun SettingsRootPreview() {
    ShroudTheme(dark = false) {
        SettingsRootContent(previewState, onRoute = {}, onOpenCalls = {}, onLogOut = {})
    }
}

@Preview(name = "Settings · 360 · dark", widthDp = 360, heightDp = 800)
@Composable
private fun SettingsRootDarkPreview() {
    ShroudTheme(dark = true) {
        SettingsRootContent(previewState.copy(isLoggingOut = true, deviceCount = null), onRoute = {}, onOpenCalls = null, onLogOut = {})
    }
}
