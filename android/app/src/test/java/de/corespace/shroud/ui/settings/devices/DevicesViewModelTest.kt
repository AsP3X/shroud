package de.corespace.shroud.ui.settings.devices

import de.corespace.shroud.core.crypto.DeviceNameSeal
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.LinkedDeviceDto
import de.corespace.shroud.ui.components.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Settings › Devices' logic (iOS `DevicesView`, `ios/shroud/Features/Main/DevicesView.swift:436-586`;
 * settings-lock §18.3 `DevicesViewModelTest`): sorting, this phone by flag or id, removals with the
 * 404 rule and the partial-failure line, the capacity copy, and the rename kind fallback. Names are
 * really sealed (the `DeviceNameSealTests` key and device id), so the sealed rename opens back.
 */
class DevicesViewModelTest {
    private val historyKey = ByteArray(32) { it.toByte() }
    private val thisPhone = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
    private val laptop = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val tablet = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val oldPhone = UUID.fromString("33333333-3333-4333-8333-333333333333")

    private fun device(id: UUID, created: String, lastSeen: String? = null, current: Boolean = false, label: DeviceNameSeal.Label? = null) =
        LinkedDeviceDto(
            id = id,
            sealedName = label?.let { DeviceNameSeal.seal(it, id, historyKey) },
            createdAt = Instant.parse(created),
            lastSeenAt = lastSeen?.let(Instant::parse),
            isCurrent = current,
        )

    private val phoneRow = device(thisPhone, "2026-09-20T10:15:00Z", "2026-10-01T08:00:00Z", current = true, label = DeviceNameSeal.Label("Pixel 9a", DeviceNameSeal.Kind.Android))
    private val laptopRow = device(laptop, "2026-09-21T10:15:00Z", "2026-09-23T08:00:00Z", label = DeviceNameSeal.Label("Chrome on Mac", DeviceNameSeal.Kind.Web))
    private val tabletRow = device(tablet, "2026-09-22T10:15:00Z", "2026-09-30T08:00:00Z", label = DeviceNameSeal.Label("Küchen-iPad ✨", DeviceNameSeal.Kind.IPad))
    private val oldPhoneRow = device(oldPhone, "2026-09-25T10:15:00Z")

    private class FakeBackend(var devices: List<LinkedDeviceDto>) : DevicesBackend {
        val revoked = ArrayList<UUID>()
        val failures = HashMap<UUID, Exception>()
        var listFailure: Exception? = null
        var listCalls = 0
        val names = ArrayList<Pair<UUID, String>>()
        var nameFailure: Exception? = null

        override suspend fun list(token: String): List<LinkedDeviceDto> {
            listCalls++
            listFailure?.let { throw it }
            return devices
        }

        override suspend fun revoke(token: String, deviceId: UUID) {
            failures[deviceId]?.let { throw it }
            revoked += deviceId
            devices = devices.filterNot { it.id == deviceId }
        }

        override suspend fun putName(token: String, deviceId: UUID, sealedName: String) {
            nameFailure?.let { throw it }
            names += deviceId to sealedName
        }
    }

    /** [DeviceNames] on a fixed history key; [locked] = the chats are locked. */
    private inner class KeyNames(var locked: Boolean = false) : DeviceNames {
        override fun open(device: LinkedDeviceDto): DeviceNameSeal.Label? =
            if (locked) null else DeviceNameSeal.open(device.sealedName, device.id, historyKey)

        override fun seal(label: DeviceNameSeal.Label, deviceId: UUID): String? =
            if (locked) null else DeviceNameSeal.seal(label, deviceId, historyKey)
    }

    private class Recorder {
        val haptics = ArrayList<Haptic>()
        val toasts = ArrayList<Toast>()
        val counts = ArrayList<Int>()
    }

    private fun TestScope.model(
        backend: FakeBackend,
        names: DeviceNames = KeyNames(),
        recorder: Recorder = Recorder(),
        token: String? = "tok",
        currentId: UUID? = thisPhone,
        scope: CoroutineScope = this,
    ) = DevicesViewModel(
        backend = backend,
        token = { token },
        currentDeviceId = { currentId },
        names = names,
        currentKind = { DeviceNameSeal.Kind.Android },
        scope = scope,
        haptic = { recorder.haptics += it },
        toast = { recorder.toasts += it },
        onCount = { recorder.counts += it },
    )

    private fun notFound() = ApiError.Server(code = "NOT_FOUND", serverMessage = "Device not found.", status = 404)

    private fun serverDown() = ApiError.Server(code = "INTERNAL", serverMessage = "Something went wrong.", status = 500)

    @Test
    fun othersAreSortedByLastActivityAndThisPhoneByFlagOrId() = runTest {
        val backend = FakeBackend(listOf(laptopRow, phoneRow, oldPhoneRow, tabletRow))
        val recorder = Recorder()
        val vm = model(backend, recorder = recorder)
        vm.load()
        val state = vm.state.value
        assertEquals(thisPhone, state.current?.id)
        // tablet seen 30 Sep, the old phone only linked 25 Sep, the laptop seen 23 Sep (`DevicesView.swift:42-46`).
        assertEquals(listOf(tablet, oldPhone, laptop), state.others.map { it.id })
        assertEquals(listOf(4), recorder.counts)
        assertEquals("Pixel 9a", state.displayName(phoneRow))
        assertEquals("Unnamed device", state.displayName(oldPhoneRow))

        // The server's flag missing: the session's device id still makes it this phone (`:436-438`).
        val unflagged = FakeBackend(listOf(phoneRow.copy(isCurrent = false), laptopRow))
        val byId = model(unflagged)
        byId.load()
        assertEquals(thisPhone, byId.state.value.current?.id)
        assertEquals(listOf(laptop), byId.state.value.others.map { it.id })

        // Neither: the list is stale ("This phone is missing from the list").
        val stale = model(FakeBackend(listOf(laptopRow)), currentId = null)
        stale.load()
        assertNull(stale.state.value.current)
    }

    @Test
    fun loadErrorsBeforeAndAfterTheFirstList() = runTest {
        val signedOut = model(FakeBackend(emptyList()), token = null)
        signedOut.load()
        assertEquals("Sign in to see your devices.", signedOut.state.value.loadError)

        val backend = FakeBackend(listOf(phoneRow))
        backend.listFailure = serverDown()
        val vm = model(backend)
        vm.load()
        assertEquals("Something went wrong.", vm.state.value.loadError)
        assertNull(vm.state.value.devices)

        backend.listFailure = null
        vm.load()
        assertNull(vm.state.value.loadError)
        val first = vm.state.value.devices

        // Once loaded, a failure is a line under the list; the list stays.
        backend.listFailure = serverDown()
        vm.load()
        assertEquals("Something went wrong.", vm.state.value.actionError)
        assertNull(vm.state.value.loadError)
        assertTrue(first === vm.state.value.devices)

        // A pull starts clean.
        backend.listFailure = null
        vm.refresh()
        assertNull(vm.state.value.actionError)
        // The same list is not replaced (`if devices != list`, `:469`).
        assertTrue(first === vm.state.value.devices)
    }

    @Test
    fun removingADevice() = runTest {
        val backend = FakeBackend(listOf(phoneRow, laptopRow, tabletRow))
        val recorder = Recorder()
        val vm = model(backend, recorder = recorder)
        vm.load()
        vm.revoke(laptopRow)
        assertTrue(vm.state.value.isRevoking(laptopRow))
        assertTrue(vm.state.value.isRemoving)
        advanceUntilIdle()
        assertEquals(listOf(laptop), backend.revoked)
        assertEquals(listOf(thisPhone, tablet), vm.state.value.devices?.map { it.id })
        assertFalse(vm.state.value.isRemoving)
        assertEquals(listOf(Haptic.Success), recorder.haptics)
        assertEquals("Chrome on Mac removed", recorder.toasts.single().message)
        assertEquals(Toast.Style.Success, recorder.toasts.single().style)
        // After the load, the removal's count, the reload's count.
        assertEquals(listOf(3, 2, 2), recorder.counts)

        // This phone is never removed here: that is Log Out.
        vm.revoke(phoneRow)
        advanceUntilIdle()
        assertEquals(listOf(laptop), backend.revoked)
    }

    /** A 404 means it is gone already: removed here, an info toast (`DevicesView.swift:142-146, 497-499`). */
    @Test
    fun aDeviceRemovedElsewhereCountsAsRemoved() = runTest {
        val backend = FakeBackend(listOf(phoneRow, laptopRow))
        backend.failures[laptop] = notFound()
        val recorder = Recorder()
        val vm = model(backend, recorder = recorder)
        vm.load()
        backend.devices = listOf(phoneRow)
        vm.revoke(laptopRow)
        advanceUntilIdle()
        assertEquals(listOf(thisPhone), vm.state.value.devices?.map { it.id })
        assertEquals("Chrome on Mac was already removed", recorder.toasts.single().message)
        assertEquals(Toast.Style.Info, recorder.toasts.single().style)
        assertTrue(recorder.haptics.isEmpty())
        assertNull(vm.state.value.actionError)
    }

    @Test
    fun aFailedRemovalSaysWhyUnderTheList() = runTest {
        val backend = FakeBackend(listOf(phoneRow, laptopRow))
        backend.failures[laptop] = serverDown()
        val recorder = Recorder()
        val vm = model(backend, recorder = recorder)
        vm.load()
        vm.revoke(laptopRow)
        advanceUntilIdle()
        assertEquals("Something went wrong.", vm.state.value.actionError)
        assertEquals(listOf(Haptic.Error), recorder.haptics)
        assertTrue(recorder.toasts.isEmpty())
        assertEquals(listOf(thisPhone, laptop), vm.state.value.devices?.map { it.id })
    }

    /** One `DELETE` each, past failures; a 404 counts as removed (`DevicesView.swift:508-544`). */
    @Test
    fun removeAllOthersWithAPartialFailure() = runTest {
        val backend = FakeBackend(listOf(phoneRow, laptopRow, tabletRow, oldPhoneRow))
        backend.failures[laptop] = serverDown()
        backend.failures[oldPhone] = notFound()
        val recorder = Recorder()
        val vm = model(backend, recorder = recorder)
        vm.load()
        vm.revokeAllOthers()
        assertTrue(vm.state.value.isRevokingAll)
        assertTrue(vm.state.value.isRevoking(laptopRow))
        advanceUntilIdle()
        assertFalse(vm.state.value.isRevokingAll)
        assertEquals(listOf(tablet), backend.revoked)
        assertEquals("1 of 3 devices could not be removed. Something went wrong.", vm.state.value.actionError)
        assertEquals(listOf(Haptic.Error), recorder.haptics)
        assertTrue(recorder.toasts.isEmpty())
    }

    @Test
    fun removeAllOthersSucceeding() = runTest {
        val backend = FakeBackend(listOf(phoneRow, laptopRow, tabletRow))
        val recorder = Recorder()
        val vm = model(backend, recorder = recorder)
        vm.load()
        vm.revokeAllOthers()
        advanceUntilIdle()
        assertEquals(setOf(laptop, tablet), backend.revoked.toSet())
        assertEquals(listOf(thisPhone), vm.state.value.devices?.map { it.id })
        assertEquals("2 devices removed", recorder.toasts.single().message)
        assertEquals(listOf(Haptic.Success), recorder.haptics)
    }

    @Test
    fun removeAllWithOneOtherDevice() = runTest {
        val backend = FakeBackend(listOf(phoneRow, laptopRow))
        backend.failures[laptop] = serverDown()
        val vm = model(backend)
        vm.load()
        vm.revokeAllOthers()
        advanceUntilIdle()
        assertEquals("The device could not be removed. Something went wrong.", vm.state.value.actionError)

        backend.failures.clear()
        val recorder = Recorder()
        val again = model(backend, recorder = recorder)
        again.load()
        again.revokeAllOthers()
        advanceUntilIdle()
        assertEquals("1 device removed", recorder.toasts.single().message)
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
        assertEquals("Unnamed device", DevicesCopy.displayName(null))
        val labels = { _: Instant -> "9:37" }
        assertEquals("Last active 9:37", DevicesCopy.lastActive(laptopRow, labels))
        assertEquals("Linked 9:37", DevicesCopy.lastActive(oldPhoneRow, labels))
    }

    /** The stored kind is kept; without a label this phone's own kind, else "other"; always custom (`DevicesView.swift:553-579`). */
    @Test
    fun renameKeepsTheKind() = runTest {
        val backend = FakeBackend(listOf(phoneRow, tabletRow, oldPhoneRow, phoneRow.copy(id = laptop, isCurrent = false, sealedName = null)))
        val recorder = Recorder()
        val vm = model(backend, recorder = recorder)
        vm.load()

        assertNull(vm.rename(tabletRow, "Kitchen"))
        assertEquals(DeviceNameSeal.Label("Kitchen", DeviceNameSeal.Kind.IPad, custom = true), opened(backend.names.last()))

        assertNull(vm.rename(oldPhoneRow, "Old phone"))
        assertEquals(DeviceNameSeal.Label("Old phone", DeviceNameSeal.Kind.Other, custom = true), opened(backend.names.last()))

        // This phone without a readable label: its own kind (Android, P4).
        val unnamedThisPhone = phoneRow.copy(sealedName = null)
        backend.devices = listOf(unnamedThisPhone)
        vm.load()
        assertNull(vm.rename(unnamedThisPhone, "My phone"))
        assertEquals(DeviceNameSeal.Label("My phone", DeviceNameSeal.Kind.Android, custom = true), opened(backend.names.last()))
        assertEquals(Haptic.Light, recorder.haptics.last())
        assertTrue(backend.listCalls >= 5)
    }

    @Test
    fun renameFailures() = runTest {
        val backend = FakeBackend(listOf(phoneRow, tabletRow))
        val names = KeyNames()
        val vm = model(backend, names = names)
        vm.load()
        assertEquals("Enter a name.", vm.rename(tabletRow, "   "))
        backend.nameFailure = ApiError.Server(code = "RATE_LIMITED", serverMessage = "Too many requests. Try again later.", status = 429)
        assertEquals("Too many requests. Try again later.", vm.rename(tabletRow, "Kitchen"))
        names.locked = true
        assertEquals("Unlock Shroud to rename devices.", vm.rename(tabletRow, "Kitchen"))
        assertEquals("Unlock Shroud to rename devices.", model(backend, token = null).rename(tabletRow, "Kitchen"))
        assertTrue(backend.names.isEmpty())
    }

    @Test
    fun latestCopyAfterARename() = runTest {
        val backend = FakeBackend(listOf(phoneRow, tabletRow))
        val vm = model(backend)
        vm.load()
        val renamed = tabletRow.copy(sealedName = DeviceNameSeal.seal(DeviceNameSeal.Label("Kitchen", DeviceNameSeal.Kind.IPad, true), tablet, historyKey))
        backend.devices = listOf(phoneRow, renamed)
        vm.load()
        assertEquals(renamed, vm.latest(tabletRow))
        assertEquals("Kitchen", vm.state.value.displayName(renamed))
        val gone = device(UUID.randomUUID(), "2026-09-20T10:15:00Z")
        assertEquals(gone, vm.latest(gone))
    }

    @Test
    fun aLockedPhoneShowsNoNames() = runTest {
        val vm = model(FakeBackend(listOf(phoneRow, tabletRow)), names = KeyNames(locked = true))
        vm.load()
        assertEquals("Unnamed device", vm.state.value.displayName(tabletRow))
        assertNull(vm.state.value.label(tabletRow))
    }

    private fun opened(entry: Pair<UUID, String>): DeviceNameSeal.Label? = DeviceNameSeal.open(entry.second, entry.first, historyKey)
}
