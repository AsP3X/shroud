package de.corespace.shroud.ui.contacts

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.contacts.ContactInviteParser
import de.corespace.shroud.core.contacts.ContactsListBlock
import de.corespace.shroud.core.contacts.ContactsListContent
import de.corespace.shroud.core.contacts.ContactsSorting
import de.corespace.shroud.core.lifecycle.AppPhase
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.ContactItemDto
import de.corespace.shroud.core.net.ContactRequestDto
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.AvatarPalette
import de.corespace.shroud.ui.components.ChatRow
import de.corespace.shroud.ui.components.GlassBarButton
import de.corespace.shroud.ui.components.GlassBarGroup
import de.corespace.shroud.ui.components.ListEntranceHost
import de.corespace.shroud.ui.components.ListLoadError
import de.corespace.shroud.ui.components.ListStateTransitions
import de.corespace.shroud.ui.components.MainScrollScreen
import de.corespace.shroud.ui.components.NameAvatar
import de.corespace.shroud.ui.components.SearchField
import de.corespace.shroud.ui.components.SkeletonChatList
import de.corespace.shroud.ui.components.Toast
import de.corespace.shroud.ui.components.ToastHost
import de.corespace.shroud.ui.components.entranceRow
import de.corespace.shroud.ui.components.rememberToastState
import de.corespace.shroud.ui.shell.ChatRoute
import de.corespace.shroud.ui.shell.LocalIsTabBarSearchActive
import de.corespace.shroud.ui.shell.LocalShellNavigation
import de.corespace.shroud.ui.shell.LocalTabBarClearance
import de.corespace.shroud.ui.shell.MainTab
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.rememberHaptics
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * The Contacts tab (iOS `ContactsView`, `ios/shroud/Features/Main/ContactsView.swift:5-339`;
 * contacts §5.1–5.3, design `Contacts` CumKS / `Contacts · Dark` BNBY4).
 *
 * Human: A glass bar with the sort capsule ("A–Z"), My QR code and Add contact; the search field
 * (shared with the tab bar's); incoming requests under "Pending" with Reject / Accept; contacts in
 * letter sections whose headers stay pinned under the bar; one of skeleton / "Can't load
 * contacts" / empty; and the "Your invite" footer with the share code and link. A tap on a contact
 * opens the chat. Add Contact and My QR Code open as sheets; a sent request is confirmed with a
 * 2.4 s toast above the tab bar.
 *
 * Agent: Reads the contacts engine ([de.corespace.shroud.core.contacts.Contacts]), presence and
 * peer activity, the session's share code and the server configuration; never writes them except
 * through the controllers. On entry it refreshes the roster and, without a share code, validates the
 * session (`:178-183`). While it is the selected tab of a resumed app it closes the contact-request
 * notifications (web-parity §7.6, `AppShell.tsx:813-816`; W2 forwarded). An App Link invite
 * (`Contacts.pendingInvite`, P10c) opens Add Contact pre-filled and is consumed; it never sends by
 * itself — the user taps Add (contacts §5.10). The shell selects this tab for it (W3-SHELL).
 */
@Composable
fun ContactsScreen(query: String, onQueryChange: (String) -> Unit) {
    val container = LocalAppContainer.current
    val contacts = container.contacts.controller
    val messaging = container.messaging.controller
    val sessions = container.auth.sessionController
    val navigation = LocalShellNavigation.current
    val scope = rememberCoroutineScope()
    val haptic = rememberHaptics()
    val toast = rememberToastState()
    val context = LocalContext.current

    val roster by contacts.contacts.collectAsState()
    val requests by contacts.incomingRequests.collectAsState()
    val listStatus by contacts.listState.collectAsState()
    val presence by contacts.presence.collectAsState()
    val activities by messaging.peerActivities.collectAsState()
    val session by sessions.session.collectAsState()
    val server by container.serverConfiguration.configuration.collectAsState()
    val pendingInvite by contacts.pendingInvite.collectAsState()
    val phase by container.appPhase.phase.collectAsState()
    val selectedTab by navigation.selection.collectAsState()

    // iOS `@State`: the order, the requests on the wire, the sheets (`ContactsView.swift:13-18`).
    var ascending by rememberSaveable { mutableStateOf(true) }
    val responding = remember { mutableStateSetOf<UUID>() }
    var showMyQr by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var addPrefill by remember { mutableStateOf<String?>(null) }
    // Every opening is a fresh sheet, as iOS builds a new `AddContactSheet` each time.
    var addSession by remember { mutableIntStateOf(0) }

    val order = remember { ContactsSorting.collator() }
    val content = remember(roster, requests, listStatus, query, ascending, order) {
        ContactsSorting.listContent(roster, requests, listStatus, query, ascending, order)
    }
    val shareCode = session?.shareCode
    val shareUrl = shareCode?.let { ContactInviteParser.shareUrl(it, server) }

    // `.task` (`:178-183`): the roster, then the share code for sessions made before it existed.
    LaunchedEffect(Unit) {
        contacts.refresh()
        if (sessions.session.value?.shareCode == null) sessions.validate()
    }

    // Looking at the requests answers their notifications (web `AppShell.tsx:813-816`).
    val onScreen = phase == AppPhase.Active && selectedTab == MainTab.Contacts
    val requestIds = requests.map { it.id }
    LaunchedEffect(onScreen, requestIds) {
        if (onScreen) container.notifications.controller.clearContactRequestNotifications()
    }

    // An App Link's invite: Add Contact, pre-filled, waiting for the user's tap on Add.
    LaunchedEffect(pendingInvite) {
        val invite = pendingInvite ?: return@LaunchedEffect
        contacts.pendingInvite.value = null
        showMyQr = false
        addPrefill = invite
        addSession++
        showAdd = true
    }

    val is24h = DateFormat.is24HourFormat(context)
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    val locale = Locale.getDefault()

    // The floating tab bar covers the bottom of the list: the last row ends at its top edge.
    val clearance = LocalTabBarClearance.current
    val systemBottom = WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding()
    val bottomExtra = (clearance - systemBottom).coerceAtLeast(0.dp)

    // `MainScrollScreen` and `ToastHost` pad by the system inset; the extra above is added here,
    // so they see no clearance (and add none twice if they learn to read it).
    CompositionLocalProvider(LocalTabBarClearance provides 0.dp) {
        Box(Modifier.fillMaxSize()) {
            ContactsList(
                content = content,
                contactsEmpty = roster.isEmpty(),
                status = { contact -> ContactStatus.row(presence[contact.userId], now, zone, locale, is24h) },
                isOnline = { contact -> presence[contact.userId]?.online == true },
                activity = { contact -> activities[contact.userId] },
                responding = responding,
                query = query,
                onQueryChange = onQueryChange,
                searchActive = LocalIsTabBarSearchActive.current,
                ascending = ascending,
                onToggleSort = { ascending = !ascending },
                shareCode = shareCode,
                shareUrl = shareUrl,
                bottomExtra = bottomExtra,
                onOpenContact = { contact -> navigation.push(ChatRoute.Conversation(contact.userId, contact.username)) },
                onRespond = { request, accept ->
                    // Accept or Reject once; both buttons wait for the answer (`:237-258`).
                    if (responding.add(request.id)) {
                        scope.launch {
                            val error = container.appScope.runDetached {
                                if (accept) contacts.accept(request) else contacts.reject(request)
                            }
                            responding.remove(request.id)
                            if (error != null) {
                                toast.show(Toast.failure(error))
                                haptic(Haptic.Error)
                            } else if (accept) {
                                haptic(Haptic.Success)
                            }
                        }
                    }
                },
                onRetry = { contacts.refresh(force = true) },
                onShowMyQr = { showMyQr = true },
                onShowAdd = {
                    addPrefill = null
                    addSession++
                    showAdd = true
                },
            )
            ToastHost(toast, bottomInset = bottomExtra)
        }
        MyQrCodeSheet(visible = showMyQr, onDismiss = { showMyQr = false })
        key(addSession) {
            AddContactSheet(
                visible = showAdd,
                prefill = addPrefill,
                onDismiss = { showAdd = false },
                // A sent request shows up nowhere in the list: the toast is the only sign (`:166-172`).
                onAdded = { message -> toast.show(Toast.success(message, ADDED_TOAST_MS)) },
            )
        }
    }
}

/** "Request sent to …" stays 2.4 s (`ContactsView.swift:170`). */
internal const val ADDED_TOAST_MS = 2_400L

/**
 * The list of the Contacts tab with its bar (`ContactsView.swift:59-156`), from plain state so it
 * is drawn in tests and previews without the engines.
 */
@Composable
internal fun ContactsList(
    content: ContactsListContent,
    contactsEmpty: Boolean,
    status: (ContactItemDto) -> String,
    isOnline: (ContactItemDto) -> Boolean,
    activity: (ContactItemDto) -> ChatPeerActivity?,
    responding: Set<UUID>,
    query: String,
    onQueryChange: (String) -> Unit,
    searchActive: Boolean,
    ascending: Boolean,
    onToggleSort: () -> Unit,
    shareCode: String?,
    shareUrl: String?,
    bottomExtra: Dp,
    onOpenContact: (ContactItemDto) -> Unit,
    onRespond: (ContactRequestDto, Boolean) -> Unit,
    onRetry: suspend () -> Unit,
    onShowMyQr: () -> Unit,
    onShowAdd: () -> Unit,
    state: LazyListState = rememberLazyListState(),
) {
    // The entrance re-arms when loaded rows replace an empty list (`.listEntranceHost(resetOn:)`, `:157`).
    ListEntranceHost(key = contactsEmpty) {
        MainScrollScreen(
            title = ContactsCopy.TITLE,
            state = state,
            leading = { SortCapsule(ascending, onToggleSort) },
            trailing = {
                // Two related actions fused into one capsule (`:76-86`).
                GlassBarGroup {
                    GlassBarButton(ShroudIcons.QrCode, ContactsCopy.MY_QR_CODE, onShowMyQr)
                    GlassBarButton(ShroudIcons.UserPlus, ContactsCopy.ADD_CONTACT, onShowAdd)
                }
            },
            header = {
                // Hidden while the tab bar's own search field is open (`:87-93`).
                AnimatedVisibility(visible = !searchActive, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.fade())) {
                    SearchField(query, onQueryChange, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
                }
            },
        ) {
            if (content.pending.isNotEmpty()) {
                stickyHeader(key = PENDING_HEADER_KEY, contentType = PinnedHeaders.CONTENT_TYPE) { index ->
                    ContactsSectionHeader(ContactsCopy.PENDING, Modifier.pinnedUnderBar(state, index))
                }
                // Server order, never filtered by the search (`:96-104`).
                itemsIndexed(content.pending, key = { _, request -> "request:${request.id}" }, contentType = { _, _ -> "request" }) { _, request ->
                    ContactRequestRow(
                        request = request,
                        isResponding = request.id in responding,
                        onRespond = { accept -> onRespond(request, accept) },
                        modifier = Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.standard(), fadeOutSpec = Motion.fade()),
                    )
                }
            }
            content.sections.forEachIndexed { sectionIndex, section ->
                stickyHeader(key = "header:${section.key}", contentType = PinnedHeaders.CONTENT_TYPE) { index ->
                    ContactsSectionHeader(
                        section.key,
                        Modifier
                            .pinnedUnderBar(state, index)
                            .animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.standard(), fadeOutSpec = Motion.fade()),
                    )
                }
                itemsIndexed(section.contacts, key = { _, contact -> "contact:${contact.userId}" }, contentType = { _, _ -> "contact" }) { rowIndex, contact ->
                    ChatRow(
                        title = contact.username,
                        subtitle = status(contact),
                        avatar = { NameAvatar(contact.username, seed = AvatarPalette.seed(contact.username, contact.userId)) },
                        activity = activity(contact),
                        onClick = { onOpenContact(contact) },
                        subtitleAccent = isOnline(contact),
                        modifier = Modifier
                            .animateItem(fadeInSpec = Motion.fade(), placementSpec = Motion.standard(), fadeOutSpec = Motion.fade())
                            // The stagger adds the section index to the row's index in it (`:124-125`).
                            .entranceRow(sectionIndex + rowIndex),
                    )
                }
            }
            item(key = "state", contentType = "state") {
                ContactsStateBlock(content.block, onRetry)
            }
            item(key = "footer", contentType = "footer") {
                ShareFooter(shareCode, shareUrl, onShowMyQr)
            }
            item(key = "end", contentType = "end") {
                Spacer(Modifier.fillMaxWidth().height(16.dp + bottomExtra))
            }
        }
    }
}

/** The one state block under the rows (`ContactsView.swift:132-146`): skeleton fades, error and empty also rise 8 dp. */
@Composable
private fun ContactsStateBlock(block: ContactsListBlock, onRetry: suspend () -> Unit) {
    val enter = ListStateTransitions.enter()
    val exit = ListStateTransitions.exit()
    AnimatedContent(
        targetState = block,
        contentKey = { it::class },
        transitionSpec = {
            if (initialState is ContactsListBlock.Skeleton || targetState is ContactsListBlock.Skeleton) {
                fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
            } else {
                enter togetherWith exit
            }
        },
        label = "contactsState",
    ) { shown ->
        when (shown) {
            ContactsListBlock.Skeleton -> SkeletonChatList(rows = SKELETON_ROWS)
            is ContactsListBlock.LoadError -> ListLoadError(shown.title, shown.message, onRetry)
            is ContactsListBlock.Empty -> ContactsEmptyState(shown.title, shown.message)
            ContactsListBlock.None -> Box(Modifier.fillMaxWidth())
        }
    }
}

/** `SkeletonChatList(count: 8)` (`ContactsView.swift:133`). */
internal const val SKELETON_ROWS = 8

private const val PENDING_HEADER_KEY = "header:pending"
