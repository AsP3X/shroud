package de.corespace.shroud.ui.settings.about

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.about.AppVersion
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.core.update.ClientUpdate
import de.corespace.shroud.core.update.ClientUpdateStatus
import de.corespace.shroud.core.update.UpdateCheckOutcome
import de.corespace.shroud.core.update.UpdateLinkOpener
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.BrandLogoMark
import de.corespace.shroud.ui.components.InsetDivider
import de.corespace.shroud.ui.components.PushedScreen
import de.corespace.shroud.ui.components.SectionHeader
import de.corespace.shroud.ui.components.SettingsCard
import de.corespace.shroud.ui.components.SettingsMetrics
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.brandTileShape
import de.corespace.shroud.ui.components.highlightRow
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.rememberToastState
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.rememberHaptics
import de.corespace.shroud.ui.update.MIN_CHECKING_MS
import de.corespace.shroud.ui.update.UpdateCopy
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Copy of Settings › About Shroud, the same on iOS (`AboutShroudView.swift`) and the web. */
object AboutCopy {
    const val TITLE = "About Shroud"
    const val APP_NAME = "Shroud"
    const val UPDATES = "Updates"
    const val SERVER = "Server"
    const val PRIVACY = "Privacy"
    const val MORE = "More"
    const val CHECKING = "Checking for updates…"
    const val CURRENT = "Shroud is up to date"
    const val REQUIRED = "Update required"
    const val FAILED = "Couldn’t check for updates"
    const val UNCHECKED = "Updates are checked automatically"
    const val UPDATE = "Update"
    const val CHECK_FOR_UPDATES = "Check for Updates"
    const val CHECK_RUNNING = "Checking…"
    const val ADDRESS = "Address"
    const val OFFICIAL_SERVER = "Official Shroud server"
    const val SERVER_VERSION = "Server version"
    const val NO_SERVER_VERSION = "—"
    const val PRIVACY_NOTE =
        "Messages, media and calls are end-to-end encrypted. They’re sealed on your devices, so the server passes them on without being able to read them."
    const val SOURCE_CODE = "Source Code"
    const val SOURCE_CODE_URL = "https://github.com/AsP3X/shroud"
    const val LICENSES = "Open-Source Licenses"
    const val LINK_FAILED = "No app on this phone can open the link."

    /** "Version 0.1.0 (1)": `versionName` and the base `versionCode`. */
    fun versionLine(version: AppVersion): String = "Version ${version.name} (${version.code})"

    /** "Version 0.2.0 is available"; without a version from the server, "A new version is available". */
    fun available(latest: String?): String = if (latest != null) "Version $latest is available" else "A new version is available"

    /** The Updates row's text for [status]. */
    fun status(status: AboutUpdateStatus): String = when (status) {
        AboutUpdateStatus.Checking -> CHECKING
        AboutUpdateStatus.Current -> CURRENT
        is AboutUpdateStatus.Available -> available(status.latestVersion)
        AboutUpdateStatus.Required -> REQUIRED
        AboutUpdateStatus.Failed -> FAILED
        AboutUpdateStatus.Unchecked -> UNCHECKED
    }

    /**
     * The Server › Address value: "Official Shroud server", or the self-hosted host as typed
     * (trimmed; the whole address when the host is blank), like iOS `addressLabel`.
     */
    fun address(configuration: ServerConfiguration): String = when (configuration.mode) {
        ServerConnectionMode.Official -> OFFICIAL_SERVER
        ServerConnectionMode.SelfHosted -> configuration.host.trim().ifEmpty { configuration.selfHostedPreview }
    }
}

/** What the Updates row says (iOS `UpdateCheckStatus`). */
sealed interface AboutUpdateStatus {
    data object Checking : AboutUpdateStatus
    data object Current : AboutUpdateStatus
    data class Available(val latestVersion: String?, val updateUrl: String?) : AboutUpdateStatus
    data object Required : AboutUpdateStatus
    data object Failed : AboutUpdateStatus

    /** No check on this server has finished yet. */
    data object Unchecked : AboutUpdateStatus

    companion object {
        /**
         * A running check wins; then a failed last check, even over an older answer, so "Check for
         * Updates" never looks as if it had worked (iOS `ClientVersionPolicy.status`); then the answer.
         */
        fun of(update: ClientUpdate, lastOutcome: UpdateCheckOutcome?, checking: Boolean): AboutUpdateStatus = when {
            checking -> Checking
            lastOutcome == UpdateCheckOutcome.Failed -> Failed
            lastOutcome == null -> Unchecked
            else -> when (update.status) {
                ClientUpdateStatus.Current -> Current
                ClientUpdateStatus.UpdateAvailable -> Available(update.latestVersion, update.updateUrl)
                ClientUpdateStatus.UpdateRequired -> Required
            }
        }
    }
}

/**
 * What About Shroud shows; [AboutScreen] builds it from the container.
 *
 * @property serverVersion the server's own version from its last answer, null before one or from a
 *   server that does not send it ("—").
 */
@Immutable
data class AboutState(
    val version: AppVersion,
    val status: AboutUpdateStatus,
    val serverAddress: String,
    val serverVersion: String?,
)

/**
 * Settings › About Shroud (iOS `AboutShroudView`; the same page on the web): this build, its update
 * status, the server, a privacy note, the source code and the open-source licenses.
 *
 * The Updates row follows `ClientUpdateChecker` (an offer "Later" hid still shows here). "Check for
 * Updates" asks at once, past the ten-minute limit, keeps "Checking…" up for at least
 * [MIN_CHECKING_MS] like the Update required screen, then ticks success, warning (an update) or
 * error; the row itself says how it went, so there is no toast. "Update" and "Source Code" open
 * their links with a plain `ACTION_VIEW` ([UpdateLinkOpener]); when nothing can open one a toast
 * says so.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenLicenses: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val checker = container.update.checker
    val update by checker.update.collectAsState()
    val checking by checker.isChecking.collectAsState()
    val outcome by checker.lastOutcome.collectAsState()
    val server by container.serverConfiguration.configuration.collectAsState()
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val toasts = rememberToastState()
    var holdingCheck by remember { mutableStateOf(false) }

    val state = AboutState(
        version = container.update.appVersion,
        status = AboutUpdateStatus.of(update, outcome, checking || holdingCheck),
        serverAddress = AboutCopy.address(server),
        serverVersion = update.serverVersion,
    )
    val open: (String, String) -> Unit = { url, failure ->
        if (!UpdateLinkOpener.open(context, url)) toasts.show(Toast.failure(failure))
    }
    Box(Modifier.fillMaxSize()) {
        AboutContent(
            state = state,
            onBack = onBack,
            onCheckForUpdates = {
                if (!holdingCheck) {
                    holdingCheck = true
                    scope.launch {
                        try {
                            val result = coroutineScope {
                                // "Checking…" stays long enough to be read, however fast the server is.
                                val floor = launch { delay(MIN_CHECKING_MS) }
                                checker.checkAgain().also { floor.join() }
                            }
                            when (result) {
                                UpdateCheckOutcome.Answered(ClientUpdateStatus.Current) -> haptic(Haptic.Success)
                                is UpdateCheckOutcome.Answered -> haptic(Haptic.Warning)
                                UpdateCheckOutcome.Failed -> haptic(Haptic.Error)
                                UpdateCheckOutcome.Skipped -> Unit
                            }
                        } finally {
                            holdingCheck = false
                        }
                    }
                }
            },
            onUpdate = { url -> open(url, UpdateCopy.LINK_FAILED) },
            onSourceCode = { open(AboutCopy.SOURCE_CODE_URL, AboutCopy.LINK_FAILED) },
            onOpenLicenses = onOpenLicenses,
        )
        ToastHost(toasts)
    }
}

/**
 * About Shroud's drawing: `PushedScreen("About Shroud")`, a 14-spaced column (padding h 16, top 8,
 * 24 at the end) with the header and four sections, each a 13 sp upper-case [SectionHeader] 6 above
 * a [SettingsCard] (`AboutShroudView.swift:34-89`).
 */
@Composable
fun AboutContent(
    state: AboutState,
    onBack: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onUpdate: (String) -> Unit,
    onSourceCode: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val colors = ShroudTheme.colors
    PushedScreen(AboutCopy.TITLE, onBack) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(state.version)
            Section(AboutCopy.UPDATES) {
                StatusRow(state.status, onUpdate)
                InsetDivider(SettingsMetrics.textInset)
                CheckRow(checking = state.status == AboutUpdateStatus.Checking, onClick = onCheckForUpdates)
            }
            Section(AboutCopy.SERVER) {
                ValueRow(AboutCopy.ADDRESS, state.serverAddress)
                InsetDivider(SettingsMetrics.textInset)
                ValueRow(AboutCopy.SERVER_VERSION, state.serverVersion ?: AboutCopy.NO_SERVER_VERSION)
            }
            Section(AboutCopy.PRIVACY) {
                ShroudText(
                    AboutCopy.PRIVACY_NOTE,
                    inter(15f, lineSpacing = 2f),
                    colors.textSecondary,
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SettingsMetrics.textInset, vertical = 12.dp),
                )
            }
            Section(AboutCopy.MORE) {
                LinkRow(AboutCopy.SOURCE_CODE, ShroudIcons.ArrowUpRightBold, onSourceCode)
                InsetDivider(SettingsMetrics.textInset)
                LinkRow(AboutCopy.LICENSES, ShroudIcons.CaretRight, onOpenLicenses)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The brand mark (72, Welcome's accent shadow), "Shroud" 22 Bold (a heading) and the version line
 * 15 `textSecondary`, selectable; spacing 8, the mark 4 further up, top 12, bottom 6.
 */
@Composable
private fun Header(version: AppVersion) {
    val colors = ShroudTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BrandLogoMark(
            72.dp,
            Modifier
                .padding(bottom = 4.dp)
                .dropShadow(brandTileShape(72.dp), Shadow(radius = 16.dp, color = colors.accent, offset = DpOffset(0.dp, 10.dp), alpha = 0.22f)),
        )
        ShroudText(AboutCopy.APP_NAME, inter(22f, FontWeight.Bold), colors.textPrimary, Modifier.semantics { heading() }, textAlign = TextAlign.Center)
        SelectionContainer {
            ShroudText(AboutCopy.versionLine(version), inter(15f), colors.textSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(title)
        SettingsCard(content = content)
    }
}

/**
 * The update status: a 24 dp glyph slot, the text 16 `textPrimary` (a polite live region, so
 * TalkBack hears the check end), and "Update" for an offer with a link. Padding h 14, v 12; the
 * glyph and text cross-fade on a change.
 */
@Composable
private fun StatusRow(status: AboutUpdateStatus, onUpdate: (String) -> Unit) {
    val colors = ShroudTheme.colors
    val fade = Motion.respecting(ShroudTheme.reduceMotion, Motion.fade<Float>())
    val url = (status as? AboutUpdateStatus.Available)?.updateUrl
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .padding(start = SettingsMetrics.textInset, end = if (url != null) 4.dp else SettingsMetrics.textInset),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = status,
            transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
            label = "about-status-glyph",
            modifier = Modifier.width(24.dp).clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
            contentKey = { it::class },
        ) { shown -> StatusGlyph(shown) }
        ShroudText(
            AboutCopy.status(status),
            inter(16f),
            colors.textPrimary,
            Modifier
                .weight(1f)
                .padding(vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        AnimatedVisibility(url != null, enter = fadeIn(fade), exit = fadeOut(fade)) {
            UpdateButton(onClick = { url?.let(onUpdate) })
        }
    }
}

/** The status glyph, 20 dp (iOS SF Symbols at 20 pt). */
@Composable
private fun StatusGlyph(status: AboutUpdateStatus) {
    val colors = ShroudTheme.colors
    when (status) {
        AboutUpdateStatus.Checking -> Spinner(colors.textSecondary, size = 18.dp)
        AboutUpdateStatus.Current -> ShroudIcon(ShroudIcons.CheckCircleFill, colors.online, size = 20.dp)
        is AboutUpdateStatus.Available -> CircleGlyph(ShroudIcons.ArrowDownBold, colors.accent)
        AboutUpdateStatus.Required -> ShroudIcon(ShroudIcons.WarningFill, colors.warningIcon, size = 20.dp)
        AboutUpdateStatus.Failed -> ShroudIcon(ShroudIcons.WarningCircleFill, colors.danger, size = 20.dp)
        AboutUpdateStatus.Unchecked -> ShroudIcon(ShroudIcons.ArrowClockwiseBold, colors.textSecondary, size = 18.dp)
    }
}

/** An arrow on a filled circle (iOS `arrow.down.circle.fill`): 20 dp [fill], the glyph 11 dp white. */
@Composable
private fun CircleGlyph(icon: ImageVector, fill: Color) {
    Box(Modifier.size(20.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        ShroudIcon(icon, Color.White, size = 11.dp)
    }
}

/**
 * "Update": an `accent` capsule, 14 sp SemiBold white, padding h 14 v 6, inside a 48 dp touch
 * target that reaches 10 dp to the capsule's sides (iOS widens it by 8 pt).
 */
@Composable
private fun UpdateButton(onClick: () -> Unit) {
    val colors = ShroudTheme.colors
    Box(
        Modifier
            .heightIn(min = ROW_MIN_HEIGHT)
            .pressable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        ShroudText(
            AboutCopy.UPDATE,
            inter(14f, FontWeight.SemiBold),
            Color.White,
            Modifier
                .clip(CircleShape)
                .background(colors.accent)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            maxLines = 1,
        )
    }
}

/** "Check for Updates" in `accent`; "Checking…" in `textSecondary` and disabled while a check runs. */
@Composable
private fun CheckRow(checking: Boolean, onClick: () -> Unit) {
    val colors = ShroudTheme.colors
    ShroudText(
        if (checking) AboutCopy.CHECK_RUNNING else AboutCopy.CHECK_FOR_UPDATES,
        inter(16f),
        if (checking) colors.textSecondary else colors.accent,
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .highlightRow(onClick = onClick, enabled = !checking)
            .padding(horizontal = SettingsMetrics.textInset, vertical = 12.dp),
    )
}

/**
 * A title 16 `textPrimary` and its value 16 `textSecondary` at the end (up to two lines, cut in the
 * middle, selectable). Padding h 14, v 12; TalkBack reads "title, value".
 */
@Composable
private fun ValueRow(title: String, value: String) {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = SettingsMetrics.textInset, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudText(title, inter(16f), colors.textPrimary)
        SelectionContainer(Modifier.weight(1f)) {
            ShroudText(
                value,
                inter(16f),
                colors.textSecondary,
                Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

/** A title 16 `textPrimary` and a 13 dp trailing [icon] in `chevron`. Padding h 14, v 12. */
@Composable
private fun LinkRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .highlightRow(onClick = onClick)
            .padding(horizontal = SettingsMetrics.textInset, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudText(title, inter(16f), colors.textPrimary, Modifier.weight(1f))
        ShroudIcon(icon, colors.chevron, size = 13.dp)
    }
}

/** Every About row is at least a 48 dp touch target. */
private val ROW_MIN_HEIGHT = 48.dp

private val previewState = AboutState(
    version = AppVersion("0.1.0", 1),
    status = AboutUpdateStatus.Available("0.2.0", "https://shroud.corespace.de/download"),
    serverAddress = AboutCopy.OFFICIAL_SERVER,
    serverVersion = "0.1.0",
)

@Preview(name = "About · 412", widthDp = 412, heightDp = 1100)
@Composable
private fun AboutPreview() {
    ShroudTheme(dark = false) {
        AboutContent(previewState, onBack = {}, onCheckForUpdates = {}, onUpdate = {}, onSourceCode = {}, onOpenLicenses = {})
    }
}

@Preview(name = "About · 360 · dark · failed", widthDp = 360, heightDp = 1100)
@Composable
private fun AboutDarkPreview() {
    ShroudTheme(dark = true) {
        AboutContent(
            previewState.copy(status = AboutUpdateStatus.Failed, serverAddress = "shroud.example.org", serverVersion = null),
            onBack = {},
            onCheckForUpdates = {},
            onUpdate = {},
            onSourceCode = {},
            onOpenLicenses = {},
        )
    }
}
