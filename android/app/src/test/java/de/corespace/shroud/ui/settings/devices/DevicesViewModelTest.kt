package de.corespace.shroud.ui.settings.devices

import de.corespace.shroud.core.crypto.DeviceNameSeal
import de.corespace.shroud.core.devices.DeviceKind
import de.corespace.shroud.core.devices.DeviceRow
import de.corespace.shroud.core.devices.DevicesController
import de.corespace.shroud.core.devices.DevicesState
import de.corespace.shroud.core.devices.RemoveOutcome
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Settings › Devices on a fake of core's K3 [DevicesController] (iOS `DevicesView`,
 * `ios/shroud/Features/Main/DevicesView.swift:436-586`; settings-lock §18.3 `DevicesViewModelTest`).
 * Sorting, this-phone detection, the 404 rule, the kind kept on a rename and the failure sentences
 * are core's and tested there (`DevicesControllerTest`); this covers what the screen adds: the load
 * error versus the line under the list, the row spinners, the haptics and toasts, the names it
 * shows (a locked phone's rows carry no label) and the Android copy.
 */
class DevicesViewModelTest {
    private val thisPhone = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
    private val laptop = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val tablet = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val oldPhone = UUID.fromString("33333333-3333-4333-8333-333333333333")

    private fun row(id: UUID, created: String, lastSeen: String? = null, current: Boolean = false, label: DeviceNameSeal.Label? = null) =
        DeviceRow(
            id = id,
            label = label,
            kind = DeviceKind.of(label),
            isThisDevice = current,
            createdAt = Instant.parse(created),
            lastSeenAt = lastSeen?.let(Instant::parse),
        )

    private val phoneRow = row(thisPhone, "2026-09-20T10:15:00Z", "2026-10-01T08:00:00Z", current = true, label = DeviceNameSeal.Label("Pixel 9a", DeviceNameSeal.Kind.Android))
    private val tabletRow = row(tablet, "2026-09-22T10:15:00Z", "2026-09-30T08:00:00Z", label = DeviceNameSeal.Label("Küchen-iPad ✨", DeviceNameSeal.Kind.IPad))
    private val oldPhoneRow = row(oldPhone, "2026-09-25T10:15:00Z")
    private val laptopRow = row(laptop, "2026-09-21T10:15:00Z", "2026-09-23T08:00:00Z", label = DeviceNameSeal.Label("Chrome on Mac", DeviceNameSeal.Kind.Web))

    /** Core's order: this phone, then by last activity (`DevicesView.swift:41-46`). */
    private val all = listOf(phoneRow, tabletRow, oldPhoneRow, laptopRow)

    /** A K3 fake: [refresh] publishes [next] (or [nextError]); removals and renames answer as set. */
    private class FakeDevices : DevicesController {
        val mutable = MutableStateFlow(DevicesState())
        override val state: StateFlow<DevicesState> = mutable

        var next: List<DeviceRow> = emptyList()
        var nextError: String? = null
        var refreshes = 0
        val removed = ArrayList<UUID>()
        var removeOutcome: RemoveOutcome = RemoveOutcome.Removed
        var removeAllOutcome: RemoveOutcome = RemoveOutcome.Removed
        var removeAllCalls = 0
        val renames = ArrayList<Pair<UUID, String>>()
        var renameAnswer: String? = null

        override suspend fun refresh() {
            refreshes++
            val error = nextError
            mutable.value = if (error != null) {
                mutable.value.copy(isLoading = false, error = error)
            } else {
                DevicesState(rows = next, isLoading = false, hasLoaded = true, error = null, capacity = 5)
            }
        }

        override suspend fun rename(id: UUID, name: String): String? {
            renames += id to name
            return renameAnswer
        }

        override suspend fun remove(id: UUID): RemoveOutcome {
            removed += id
            if (removeOutcome == RemoveOutcome.Removed) next = next.filterNot { it.id == id }
            (removeOutcome as? RemoveOutcome.Failed)?.let { nextError = it.message }
            refresh()
            return removeOutcome
        }

        override suspend fun removeAllOthers(): RemoveOutcome {
            removeAllCalls++
            return removeAllOutcome
        }
    }

    private class Recorder {
        val haptics = ArrayList<Haptic>()
        val toasts = ArrayList<Toast>()
    }

    private fun TestScope.model(devices: FakeDevices, recorder: Recorder = Recorder()) =
        DevicesViewModel(devices, actionScope = this, haptic = { recorder.haptics += it }, toast = { recorder.toasts += it })

    @Test
    fun theListIsCoresWithThisPhoneFirst() = runTest {
        val devices = FakeDevices().apply { next = all }
        val vm = model(devices)
        // Before the first list: neither rows nor an error, so the loading card shows.
        assertNull(vm.state.rows)
        assertNull(vm.state.loadError)
        vm.refresh()
        val state = vm.state
        assertEquals(thisPhone, state.current?.id)
        assertEquals(listOf(tablet, oldPhone, laptop), state.others.map { it.id })
        assertEquals(4, state.rows?.size)
        assertSame(devices.state, vm.devicesState)
        assertEquals("Pixel 9a", state.displayName(phoneRow))
        assertEquals("Unnamed device", state.displayName(oldPhoneRow))

        // The server list without this phone: "This phone is missing from the list".
        devices.next = listOf(laptopRow)
        vm.refresh()
        assertNull(vm.state.current)
    }

    @Test
    fun aLockedPhoneShowsTheKindNounOrUnnamed() {
        // K3: `label` is null while the chats are locked; a kind core still knows names the row.
        val locked = phoneRow.copy(label = null, kind = DeviceKind.Android)
        assertEquals("Android app", DevicesCopy.displayName(locked))
        assertEquals("Unnamed device", DevicesCopy.displayName(tabletRow.copy(label = null, kind = DeviceKind.Unknown)))
        assertEquals("Unnamed device", DevicesCopy.displayName(row(laptop, "2026-09-21T10:15:00Z", label = DeviceNameSeal.Label("  ", DeviceNameSeal.Kind.Other))))
    }

    @Test
    fun loadErrorsBeforeAndAfterTheFirstList() = runTest {
        val devices = FakeDevices().apply { nextError = "Sign in to see your devices." }
        val vm = model(devices)
        vm.refresh()
        assertEquals("Sign in to see your devices.", vm.state.loadError)
        assertNull(vm.state.actionError)
        assertNull(vm.state.rows)

        devices.nextError = null
        devices.next = all
        vm.refresh()
        assertNull(vm.state.loadError)
        val first = vm.state.rows

        // Once loaded, core keeps the rows and its sentence is a line under the list.
        devices.nextError = "Something went wrong."
        vm.refresh()
        assertEquals("Something went wrong.", vm.state.actionError)
        assertNull(vm.state.loadError)
        assertEquals(first, vm.state.rows)
        assertEquals(3, devices.refreshes)
    }

    @Test
    fun removingADevice() = runTest {
        val devices = FakeDevices().apply { next = listOf(phoneRow, tabletRow, laptopRow) }
        val recorder = Recorder()
        val vm = model(devices, recorder)
        vm.refresh()
        vm.revoke(laptopRow)
        assertTrue(vm.state.isRevoking(laptopRow))
        assertTrue(vm.state.isRemoving)
        // A second tap while it runs does nothing.
        vm.revoke(laptopRow)
        advanceUntilIdle()
        assertEquals(listOf(laptop), devices.removed)
        assertEquals(listOf(thisPhone, tablet), vm.state.rows?.map { it.id })
        assertFalse(vm.state.isRemoving)
        assertEquals(listOf(Haptic.Success), recorder.haptics)
        assertEquals("Chrome on Mac removed", recorder.toasts.single().message)
        assertEquals(Toast.Style.Success, recorder.toasts.single().style)

        // This phone is never removed here: that is Log Out.
        vm.revoke(phoneRow)
        advanceUntilIdle()
        assertEquals(listOf(laptop), devices.removed)
    }

    @Test
    fun aFailedRemovalSaysWhyUnderTheList() = runTest {
        val devices = FakeDevices().apply {
            next = listOf(phoneRow, laptopRow)
            removeOutcome = RemoveOutcome.Failed("Something went wrong.")
        }
        val recorder = Recorder()
        val vm = model(devices, recorder)
        vm.refresh()
        vm.revoke(laptopRow)
        advanceUntilIdle()
        assertEquals("Something went wrong.", vm.state.actionError)
        assertEquals(listOf(Haptic.Error), recorder.haptics)
        assertTrue(recorder.toasts.isEmpty())
        assertFalse(vm.state.isRevoking(laptopRow))
    }

    /** One `DELETE` each, past failures, is core's; the screen adds the spinner, haptic and toast (`DevicesView.swift:508-544`). */
    @Test
    fun removeAllOthers() = runTest {
        val devices = FakeDevices().apply { next = listOf(phoneRow, laptopRow, tabletRow) }
        val recorder = Recorder()
        val vm = model(devices, recorder)
        vm.refresh()
        vm.revokeAllOthers()
        assertTrue(vm.state.isRevokingAll)
        assertTrue(vm.state.isRevoking(laptopRow))
        vm.revokeAllOthers()
        advanceUntilIdle()
        assertEquals(1, devices.removeAllCalls)
        assertFalse(vm.state.isRevokingAll)
        assertEquals("2 devices removed", recorder.toasts.single().message)
        assertEquals(listOf(Haptic.Success), recorder.haptics)

        devices.removeAllOutcome = RemoveOutcome.Partial(removed = 1, failed = 1)
        vm.revokeAllOthers()
        advanceUntilIdle()
        assertEquals(listOf(Haptic.Success, Haptic.Error), recorder.haptics)
        assertEquals(1, recorder.toasts.size)

        devices.removeAllOutcome = RemoveOutcome.Failed("The device could not be removed. Something went wrong.")
        vm.revokeAllOthers()
        advanceUntilIdle()
        assertEquals(Haptic.Error, recorder.haptics.last())
        assertEquals(1, recorder.toasts.size)

        // Nothing to remove: nothing asked.
        devices.next = listOf(phoneRow)
        vm.refresh()
        vm.revokeAllOthers()
        advanceUntilIdle()
        assertEquals(3, devices.removeAllCalls)
    }

    @Test
    fun renameForwardsAndShowsCoresSentence() = runTest {
        val devices = FakeDevices().apply { next = listOf(phoneRow, tabletRow) }
        val recorder = Recorder()
        val vm = model(devices, recorder)
        vm.refresh()
        assertNull(vm.rename(tabletRow, "Kitchen"))
        assertEquals(tablet to "Kitchen", devices.renames.single())
        assertEquals(listOf(Haptic.Light), recorder.haptics)
        devices.renameAnswer = "Unlock Shroud to rename devices."
        assertEquals("Unlock Shroud to rename devices.", vm.rename(tabletRow, "Kitchen"))
        assertEquals(listOf(Haptic.Light), recorder.haptics)
    }

    @Test
    fun latestCopyAfterARename() = runTest {
        val devices = FakeDevices().apply { next = listOf(phoneRow, tabletRow) }
        val vm = model(devices)
        vm.refresh()
        val renamed = tabletRow.copy(label = DeviceNameSeal.Label("Kitchen", DeviceNameSeal.Kind.IPad, true))
        devices.next = listOf(phoneRow, renamed)
        vm.refresh()
        assertEquals(renamed, vm.latest(tabletRow))
        assertEquals("Kitchen", vm.state.displayName(vm.latest(tabletRow)))
        assertEquals(oldPhoneRow, vm.latest(oldPhoneRow))
    }

    @Test
    fun capacityCopy() {
        assertEquals(
            "You can sign in on 4 more devices. A logged-out device stays listed until it signs in again or you remove it.",
            DevicesCopy.capacityFooter(1),
        )
        assertEquals(
            "You can sign in on 3 more devices. A logged-out device stays listed until it signs in again or you remove it.",
            DevicesCopy.capacityFooter(2),
        )
        assertEquals(
            "You can sign in on 2 more devices. A logged-out device stays listed until it signs in again or you remove it.",
            DevicesCopy.capacityFooter(3),
        )
        assertEquals(
            "You can sign in on 1 more device. A logged-out device stays listed until it signs in again or you remove it.",
            DevicesCopy.capacityFooter(4),
        )
        val full = "Your account is at the limit. A new sign-in takes over a device that has been logged out; if every device is still signed in, it is refused until you remove one here."
        assertEquals(full, DevicesCopy.capacityFooter(5))
        assertEquals(full, DevicesCopy.capacityFooter(6))
        assertEquals("4 of 5", DevicesCopy.capacityValue(4))
        assertFalse(DevicesCopy.isFull(4))
        assertTrue(DevicesCopy.isFull(5))
        assertEquals("Other devices", DevicesCopy.otherDevicesHeader(0))
        assertEquals("Other devices — 3", DevicesCopy.otherDevicesHeader(3))
        assertEquals("1 device removed", DevicesCopy.removedCount(1))
        assertEquals("2 devices removed", DevicesCopy.removedCount(2))
    }

    @Test
    fun androidWording() {
        assertEquals("Active now · This phone", DevicesCopy.activeNow("phone"))
        assertEquals("This tablet · Active now", DevicesCopy.detailsActiveNow("tablet"))
        assertEquals("This phone is missing from the list. Pull to refresh.", DevicesCopy.missingFromList("phone"))
        assertEquals("Signs out every device except this phone.", DevicesCopy.removeAllFooter("phone"))
        assertEquals(
            "Only this phone stays signed in. They are signed out right away and erase everything of your account on them: messages, keys and files, as soon as they are online or next opened. What they already sent stays in your chats. Signing in there again takes your password and 12-word phrase.",
            DevicesCopy.confirmAllMessage("phone"),
        )
        assertEquals(
            "To remove this phone from your account, use Log Out in Settings. It also erases everything Shroud keeps here.",
            DevicesCopy.currentDeviceNote("phone"),
        )
        assertEquals("Remove Pixel 9a?", DevicesCopy.confirmTitle("Pixel 9a"))
        assertEquals("Remove this device?", DevicesCopy.confirmTitle(null))
        assertEquals("Remove 2", DevicesCopy.confirmAllButton(2))
        assertEquals("Unnamed device", DevicesCopy.displayName(DeviceNameSeal.Label("   ", DeviceNameSeal.Kind.Other)))
        assertEquals("Unnamed device", DevicesCopy.displayName(null as DeviceNameSeal.Label?))
        val labels = { _: Instant -> "9:37" }
        assertEquals("Last active 9:37", DevicesCopy.lastActive(laptopRow, labels))
        assertEquals("Linked 9:37", DevicesCopy.lastActive(oldPhoneRow, labels))
    }
}
