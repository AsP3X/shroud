package de.corespace.shroud.ui.settings

import de.corespace.shroud.core.crypto.DeviceNameSeal
import de.corespace.shroud.core.devices.DeviceKind
import de.corespace.shroud.core.devices.DeviceRow
import de.corespace.shroud.core.devices.DevicesState
import de.corespace.shroud.core.net.BlockItemDto
import de.corespace.shroud.core.net.ConversationPeerDto
import de.corespace.shroud.core.net.DEVICE_LIMIT
import de.corespace.shroud.core.net.PrivacySettingsDto
import de.corespace.shroud.core.push.Distributor
import de.corespace.shroud.core.push.NoPushReason
import de.corespace.shroud.core.push.PushDelivery
import de.corespace.shroud.core.push.UnifiedPushState
import de.corespace.shroud.ui.settings.devices.DeviceRemovals
import de.corespace.shroud.ui.settings.devices.DevicesUiState
import de.corespace.shroud.ui.settings.notifications.MutedChat
import de.corespace.shroud.ui.settings.push.DeliveryState
import java.time.Instant
import java.util.UUID

/**
 * The data the Settings B screens are drawn with in the UI and render tests (W3-SETTINGS-B, W3-PUSH
 * UI half): the design's devices (`Gchkq`: "Pixel 9a" this phone, "Chrome on Mac", "iPad Air"), a
 * muted chat, a blocked user, and the delivery states of W3-DESIGN's Delivery frames.
 */
internal object SettingsBFixtures {
    val thisPhone: UUID = UUID.fromString("5f0c2a1e-8b3d-4c6f-9a2e-1d7b3c5e9f01")
    val chromeOnMac: UUID = UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
    val iPadAir: UUID = UUID.fromString("b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e")
    val macBook: UUID = UUID.fromString("c3d4e5f6-a7b8-4c9d-8e1f-2a3b4c5d6e7f")
    val windowsPc: UUID = UUID.fromString("d4e5f6a7-b8c9-4d0e-9f2a-3b4c5d6e7f80")

    fun at(iso: String): Instant = Instant.parse(iso)

    private fun device(
        id: UUID,
        name: String?,
        kind: DeviceNameSeal.Kind,
        current: Boolean = false,
        created: String,
        lastSeen: String?,
    ): DeviceRow {
        val label = name?.let { DeviceNameSeal.Label(it, kind) }
        return DeviceRow(id, label, DeviceKind.of(label), current, at(created), lastSeen?.let(::at))
    }

    /** Sealed kind 4: the green phone tile, "Android app". */
    val pixel = device(thisPhone, "Pixel 9a", DeviceNameSeal.Kind.Android, current = true, created = "2026-09-14T18:49:00Z", lastSeen = "2026-10-02T07:59:00Z")
    val chrome = device(chromeOnMac, "Chrome on Mac", DeviceNameSeal.Kind.Web, created = "2026-09-14T18:49:00Z", lastSeen = "2026-10-02T07:37:00Z")
    val ipad = device(iPadAir, "iPad Air", DeviceNameSeal.Kind.IPad, created = "2026-09-10T12:00:00Z", lastSeen = "2026-09-23T16:20:00Z")
    val mac = device(macBook, "Niklas’s MacBook", DeviceNameSeal.Kind.Other, created = "2026-09-01T09:00:00Z", lastSeen = "2026-09-20T10:00:00Z")
    val pc = device(windowsPc, "Windows desktop", DeviceNameSeal.Kind.Other, created = "2026-08-30T09:00:00Z", lastSeen = null)

    fun devices(rows: List<DeviceRow>, error: String? = null, removals: DeviceRemovals = DeviceRemovals()) = DevicesUiState(
        DevicesState(rows = rows, hasLoaded = true, error = error, capacity = DEVICE_LIMIT),
        removals,
    )

    val designDevices = listOf(pixel, chrome, ipad)

    val loading = DevicesUiState(DevicesState(isLoading = true))
    val loadFailed = DevicesUiState(DevicesState(error = "The Internet connection appears to be offline."))

    /** `ChatListFormatting.timeLabel` stand-in for the tests: today's time, else the date. */
    fun timeLabel(instant: Instant): String = when (instant) {
        chrome.lastSeenAt -> "9:37"
        ipad.lastSeenAt -> "Sep 23, 2026"
        mac.lastSeenAt -> "Sep 20, 2026"
        else -> "Aug 30, 2026"
    }

    fun longDate(instant: Instant): String = when (instant) {
        at("2026-09-14T18:49:00Z") -> "14 September 2026 at 20:49"
        at("2026-10-02T07:37:00Z") -> "2 October 2026 at 09:37"
        else -> "1 October 2026 at 12:00"
    }

    val mom = ConversationPeerDto(UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"), "Mom")
    val devon = ConversationPeerDto(UUID.fromString("1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed"), "devon_lane")
    val mutedChats = listOf(MutedChat(mom, "Muted"), MutedChat(devon, "Muted until 18:00"))

    val promo = BlockItemDto(UUID.fromString("6a1f3b2c-1d4e-4f5a-8b6c-7d8e9f0a1b2c"), "promo_deals", at("2026-09-01T10:00:00Z"))

    val privacy = PrivacySettingsDto(allowPeerChatDelete = false)

    // ---- delivery (W3-DESIGN: one distributor, several, none, refused host, background on/off with battery) ----

    val ntfy = Distributor("io.heckel.ntfy", "ntfy")
    val nextPush = Distributor("org.unifiedpush.distributor.nextpush", "NextPush")

    fun delivery(up: UnifiedPushState, background: Boolean = false, battery: Boolean = false, distributors: List<Distributor> = emptyList()) =
        DeliveryState(PushDelivery(up, backgroundConnection = background, batteryUnrestricted = battery), distributors)

    val ntfyRegistered = delivery(UnifiedPushState.Registered(ntfy.packageName, ntfy.label), distributors = listOf(ntfy))
    val ntfyRegistering = delivery(UnifiedPushState.Registering(ntfy.packageName), distributors = listOf(ntfy))
    val noneInstalled = delivery(UnifiedPushState.Unavailable(NoPushReason.NoDistributorInstalled))
    val severalToChoose = delivery(UnifiedPushState.Unavailable(NoPushReason.NoneChosen), distributors = listOf(ntfy, nextPush))
    val refusedHost = delivery(UnifiedPushState.Unavailable(NoPushReason.ServerRefusedHost), distributors = listOf(ntfy))
    val distributorFailed = delivery(UnifiedPushState.Unavailable(NoPushReason.DistributorFailed), distributors = listOf(ntfy, nextPush))
    val backgroundOptimized = delivery(UnifiedPushState.Unavailable(NoPushReason.NoDistributorInstalled), background = true, battery = false)
    val backgroundUnrestricted = delivery(UnifiedPushState.Unavailable(NoPushReason.NoDistributorInstalled), background = true, battery = true)
    val bothPaths = delivery(UnifiedPushState.Registered(ntfy.packageName, ntfy.label), background = true, battery = true, distributors = listOf(ntfy))
}
