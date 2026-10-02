package de.corespace.shroud.ui.conversation.attach

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.ui.components.ListEntranceHost
import de.corespace.shroud.ui.components.SheetStyle
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudSheet
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.Spinner
import de.corespace.shroud.ui.components.entranceRow
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.components.shimmering
import de.corespace.shroud.ui.media.PickedPhoto
import de.corespace.shroud.ui.permissions.openAppSettings
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The attach sheet (iOS `ChatAttachSheet`, `ChatAttachSheet.swift:5-326`, presented at
 * `ConversationView.swift:389-404`; design w4lZ1; conversation-compose-media §7): the floating
 * compact sheet with the Recents strip, two rows of options and Cancel.
 *
 * Leaving it by any path — Cancel, an option, "All Photos", a swipe down, the scrim, Back — cancels
 * a Recents original still loading, so a slow read can't open compose with a photo the user
 * abandoned (`:186-187`).
 *
 * @param onSelect an option was tapped (the host closes the sheet and acts, CV:1723-1738).
 * @param onPickImage a Recents tile's photo is ready for compose (CV:396-399).
 * @param accessRequested whether the photo permission was asked for before (a device fact).
 */
@Composable
fun ChatAttachSheet(
    visible: Boolean,
    onSelect: (ChatAttachOption) -> Unit,
    onCancel: () -> Unit,
    onPickImage: (PickedPhoto) -> Unit,
    decodePreview: suspend (MediaImageSource, Int) -> Bitmap?,
    accessRequested: () -> Boolean,
    markAccessRequested: () -> Unit,
) {
    ShroudSheet(visible = visible, onDismiss = onCancel, style = SheetStyle.Compact, paneTitle = "Attach") {
        AttachSheetContent(
            visible = visible,
            onSelect = onSelect,
            onCancel = onCancel,
            onPickImage = onPickImage,
            decodePreview = decodePreview,
            accessRequested = accessRequested,
            markAccessRequested = markAccessRequested,
        )
    }
}

@Composable
private fun AttachSheetContent(
    visible: Boolean,
    onSelect: (ChatAttachOption) -> Unit,
    onCancel: () -> Unit,
    onPickImage: (PickedPhoto) -> Unit,
    decodePreview: suspend (MediaImageSource, Int) -> Bitmap?,
    accessRequested: () -> Boolean,
    markAccessRequested: () -> Unit,
) {
    val colors = ShroudTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnPickImage by rememberUpdatedState(onPickImage)
    val currentDecode by rememberUpdatedState(decodePreview)

    var access by remember { mutableStateOf(PhotoAccessRules.current(context, accessRequested())) }
    var photos by remember { mutableStateOf<List<RecentPhoto>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var loadingUri by remember { mutableStateOf<Uri?>(null) }
    val jobs = remember { AttachJobs() }

    fun refresh() {
        access = PhotoAccessRules.current(context, accessRequested())
        if (!access.showsPhotos) {
            jobs.load?.cancel()
            photos = emptyList()
            return
        }
        jobs.load?.cancel()
        jobs.load = scope.launch {
            val newest = RecentPhotos.load(context.contentResolver)
            photos = newest
            loaded = true
        }
    }

    var askedFromCard by remember { mutableStateOf(false) }
    val requestAccess = rememberPhotoAccessRequest { _, dialogShown ->
        // The system no longer asks: the card's tap goes to Settings instead (§7.4).
        if (askedFromCard && !dialogShown && !PhotoAccessRules.current(context, true).showsPhotos) openAppSettings(context)
        askedFromCard = false
        refresh()
    }

    fun cancelPick() {
        jobs.pick?.cancel()
        jobs.pick = null
        loadingUri = null
    }

    fun pick(photo: RecentPhoto) {
        if (loadingUri != null) return
        loadingUri = photo.uri
        jobs.pick = scope.launch {
            val picked = try {
                RecentPhotos.original(photo, currentDecode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                loadingUri = null
            }
            // Cancelled meanwhile (Cancel, a swipe away, another option): the photo is dropped (`:279-280`).
            if (!isActive) return@launch
            jobs.pick = null
            currentOnPickImage(picked)
        }
    }

    // iOS asks once, as the sheet opens, when it was never decided (`ChatAttachSheet.swift:315-325`).
    LaunchedEffect(Unit) {
        if (access == PhotoAccess.NotAsked) {
            markAccessRequested()
            requestAccess()
        } else {
            refresh()
        }
    }
    LaunchedEffect(visible) {
        if (!visible) cancelPick()
    }
    // Coming back from Settings with access changed (§7.4).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = PhotoAccessRules.current(context, accessRequested())
                if (now != access) refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            jobs.pick?.cancel()
            jobs.load?.cancel()
        }
    }

    ListEntranceHost(key = Unit) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The sheet's own grab capsule (`:38-41`).
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(colors.separator),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RecentsHeader(
                    access = access,
                    onLink = {
                        cancelPick()
                        if (access == PhotoAccess.Partial) requestAccess() else onSelect(ChatAttachOption.Photos)
                    },
                )
                val body = when {
                    access == PhotoAccess.Denied -> RecentsBody.Denied
                    !loaded -> RecentsBody.Loading
                    photos.isEmpty() -> RecentsBody.Empty
                    else -> RecentsBody.Tiles
                }
                AnimatedContent(
                    targetState = body,
                    transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) },
                    label = "recents",
                ) { state ->
                    when (state) {
                        RecentsBody.Denied -> NoPhotoAccessCard(onAllow = {
                            askedFromCard = true
                            requestAccess()
                        })
                        RecentsBody.Loading -> LoadingTiles()
                        RecentsBody.Empty -> ShroudText(
                            "No recent photos",
                            inter(13f),
                            colors.textSecondary,
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        )
                        RecentsBody.Tiles -> RecentTiles(photos = photos, loadingUri = loadingUri, onPick = ::pick)
                    }
                }
            }

            OptionRow(ChatAttachOption.firstRow, startIndex = 0) { option ->
                cancelPick()
                onSelect(option)
            }
            OptionRow(ChatAttachOption.secondRow, startIndex = ChatAttachOption.firstRow.size) { option ->
                cancelPick()
                onSelect(option)
            }

            Box(
                Modifier
                    .padding(bottom = 4.dp)
                    .fillMaxWidth()
                    .height(50.dp)
                    .pressable(scale = 0.975f, dimming = 0.06f) {
                        cancelPick()
                        onCancel()
                    }
                    .clip(CircleShape)
                    .background(colors.backgroundGrouped),
                contentAlignment = Alignment.Center,
            ) {
                ShroudText("Cancel", inter(17f, FontWeight.SemiBold), colors.accent)
            }
        }
    }
}

/** The fetches the sheet runs; cancelled when it goes. */
private class AttachJobs {
    var load: Job? = null
    var pick: Job? = null
}

private enum class RecentsBody { Loading, Empty, Tiles, Denied }

/** "RECENTS" · "All Photos ›" (`ChatAttachSheet.swift:44-66`), or "SELECTED PHOTOS" · "Manage ›" (Sy9qO). */
@Composable
private fun RecentsHeader(access: PhotoAccess, onLink: () -> Unit) {
    val colors = ShroudTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ShroudText(
            PhotoAccessRules.header(access),
            inter(13f, FontWeight.SemiBold),
            colors.textSecondary,
            Modifier.semantics { heading() },
        )
        Spacer(Modifier.weight(1f))
        val link = PhotoAccessRules.linkTitle(access)
        Row(
            Modifier
                .heightIn(min = 44.dp)
                .pressable(scale = 0.94f, onClick = onLink)
                .semantics(mergeDescendants = true) {}
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShroudText(link, inter(13f, FontWeight.SemiBold), colors.accent)
            ShroudIcon(ShroudIcons.ChevronRight, colors.accent, size = 14.dp)
        }
    }
}

/** Shimmering tiles instead of spinners — the strip's shape is already known (`:88-99`). */
@Composable
private fun LoadingTiles() {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .shimmering()
            .clearAndSetSemantics { contentDescription = "Loading recent photos" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(LOADING_TILES) {
            Box(
                Modifier
                    .size(TILE_SIZE)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.backgroundGrouped),
            )
        }
    }
}

/** The thumbnails; one original loads at a time (`:110-145`). */
@Composable
private fun RecentTiles(photos: List<RecentPhoto>, loadingUri: Uri?, onPick: (RecentPhoto) -> Unit) {
    val count = photos.size
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        itemsIndexed(photos, key = { _, photo -> photo.uri.toString() }) { index, photo ->
            val loading = loadingUri == photo.uri
            val image = remember(photo) { photo.thumbnail.asImageBitmap() }
            Box(
                Modifier
                    .entranceRow(index)
                    .size(TILE_SIZE)
                    .pressable(enabled = loadingUri == null, scale = 0.93f, dimming = 0.12f) { onPick(photo) }
                    .semantics {
                        contentDescription = "Recent photo ${index + 1} of $count"
                        if (loading) stateDescription = "Loading"
                    }
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (loading) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                        Spinner(Color.White)
                    }
                }
            }
        }
    }
}

/**
 * The denied state (design vZsy8; Android copy replaces iOS's "Allow Photos access in Settings…"
 * line, §7.4): a grouped card with the "Allow Access" button.
 */
@Composable
private fun NoPhotoAccessCard(onAllow: () -> Unit) {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.backgroundGrouped)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudIcon(ShroudIcons.Images, colors.textSecondary, size = 24.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ShroudText("Recent photos are hidden", inter(15f, FontWeight.SemiBold), colors.textPrimary)
            ShroudText("Allow access to pick from your latest photos here.", inter(13f), colors.textSecondary)
        }
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .pressable(scale = 0.94f, onClick = onAllow),
            contentAlignment = Alignment.Center,
        ) {
            ShroudText("Allow Access", inter(15f, FontWeight.SemiBold), colors.accentText, maxLines = 1)
        }
    }
}

/** One row of four options (`optionRow`, `ChatAttachSheet.swift:190-223`). */
@Composable
private fun OptionRow(options: List<ChatAttachOption>, startIndex: Int, onSelect: (ChatAttachOption) -> Unit) {
    val colors = ShroudTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, option ->
            Column(
                Modifier
                    .weight(1f)
                    .entranceRow(startIndex + index)
                    .pressable(scale = 0.9f) { onSelect(option) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // The title alone names the button.
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(option.circleFill(colors)),
                    contentAlignment = Alignment.Center,
                ) {
                    ShroudIcon(option.icon, option.iconColor(colors), size = 22.dp)
                }
                ShroudText(
                    option.title,
                    inter(12f, FontWeight.Medium),
                    colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val TILE_SIZE = 96.dp
private const val LOADING_TILES = 4
