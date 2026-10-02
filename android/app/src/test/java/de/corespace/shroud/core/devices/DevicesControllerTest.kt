package de.corespace.shroud.core.devices

import de.corespace.shroud.core.auth.Session
import de.corespace.shroud.core.crypto.DeviceNameSeal
import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.DEVICE_LIMIT
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.LinkedDeviceDto
import de.corespace.shroud.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Sort, this-phone detection, rename sealing and removal against a fake device list.
 * No server and no account.
 */
class DevicesControllerTest {
    @get:Rule val main = MainDispatcherRule()

    private val userId = "bc26c1ed-22cb-4a74-b98f-581c6faca09d"
    private val thisPhone = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
    private val laptop = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val tablet = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val oldPhone = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val history = ByteArray(32) { it.toByte() }

    @Test
    fun emptyUntilTheFirstLoadThenThisPhoneFirstByLastActivity() = runTest(main.dispatcher) {
        val harness = harness()
        val before = harness.controller.state.value
        assertTrue(before.rows.isEmpty())
        assertFalse(before.isLoading)
        assertFalse(before.hasLoaded)
        assertNull(before.error)
        assertNull(before.capacity)

        harness.controller.refresh()
        val rows = harness.controller.state.value.rows
        assertEquals(listOf(thisPhone, tablet, oldPhone, laptop), rows.map { it.id })
        assertTrue(rows.first().isThisDevice)
        assertTrue(rows.drop(1).none { it.isThisDevice })
        assertEquals(DEVICE_LIMIT, harness.controller.state.value.capacity)
        assertTrue(harness.controller.state.value.hasLoaded)
        assertNull(harness.controller.state.value.error)
    }

    @Test
    fun anUnflaggedSessionDeviceIsStillThisPhone() = runTest(main.dispatcher) {
        val harness = harness(flagCurrent = false)
        harness.devices[harness.devices.indexOfFirst { it.id == thisPhone }] =
            device(thisPhone, "2026-09-20T00:00:00Z", "2026-09-01T00:00:00Z", current = false)
        harness.controller.refresh()
        val rows = harness.controller.state.value.rows
        assertEquals(thisPhone, rows.first().id)
        assertTrue(rows.first().isThisDevice)
        assertEquals(listOf(tablet, oldPhone, laptop), rows.drop(1).map { it.id })
    }

    @Test
    fun noSessionDeviceMeansNoCurrentRow() = runTest(main.dispatcher) {
        val other = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val harness = harness(deviceId = other, flagCurrent = false)
        harness.controller.refresh()
        val rows = harness.controller.state.value.rows
        assertTrue(rows.none { it.isThisDevice })
        assertEquals(listOf(thisPhone, tablet, oldPhone, laptop), rows.map { it.id })
    }

    @Test
    fun signedOutAndFailedLoadsKeepWhatWasAlreadyShown() = runTest(main.dispatcher) {
        val harness = harness(session = null)
        harness.controller.refresh()
        assertEquals(DevicesController.SIGN_IN, harness.controller.state.value.error)
        assertFalse(harness.controller.state.value.hasLoaded)
        assertEquals(0, harness.listCalls)

        harness.session = session()
        harness.controller.refresh()
        assertEquals(4, harness.controller.state.value.rows.size)

        harness.listError = ApiError.Server(ErrorCodes.INTERNAL_ERROR, "Could not load devices.", 500)
        harness.controller.refresh()
        assertEquals(4, harness.controller.state.value.rows.size)
        assertEquals("Could not load devices.", harness.controller.state.value.error)
        assertEquals(DEVICE_LIMIT, harness.controller.state.value.capacity)
        assertTrue(harness.controller.state.value.hasLoaded)

        harness.session = null
        harness.listError = null
        harness.controller.refresh()
        assertEquals(4, harness.controller.state.value.rows.size)
        assertEquals(DevicesController.SIGN_IN, harness.controller.state.value.error)
    }

    @Test
    fun aFailedFirstLoadLeavesTheListEmpty() = runTest(main.dispatcher) {
        val harness = harness()
        harness.listError = ApiError.Server(ErrorCodes.INTERNAL_ERROR, "Could not load devices.", 500)
        harness.controller.refresh()
        val state = harness.controller.state.value
        assertTrue(state.rows.isEmpty())
        assertFalse(state.hasLoaded)
        assertNull(state.capacity)
        assertEquals("Could not load devices.", state.error)
    }

    @Test
    fun theFirstLoadIsTheOnlyOneThatShowsLoadingAndOverlappingLoadsShareIt() = runTest(main.dispatcher) {
        val harness = harness()
        val gate = CompletableDeferred<Unit>()
        harness.listGate = gate
        val first = launch { harness.controller.refresh() }
        val second = launch { harness.controller.refresh() }
        assertEquals(1, harness.listCalls)
        assertTrue(harness.controller.state.value.isLoading)
        gate.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, harness.listCalls)
        assertFalse(harness.controller.state.value.isLoading)

        val again = CompletableDeferred<Unit>()
        harness.listGate = again
        val third = launch { harness.controller.refresh() }
        assertFalse(harness.controller.state.value.isLoading)
        again.complete(Unit)
        third.join()
    }

    @Test
    fun namesStaySealedUntilTheChatsAreOpen() = runTest(main.dispatcher) {
        val harness = harness(unlocked = null)
        harness.controller.refresh()
        assertTrue(harness.controller.state.value.rows.all { it.label == null && it.kind == DeviceKind.Unknown })

        harness.unlockedUserId = userId.uppercase()
        harness.controller.refresh()
        val mine = harness.controller.state.value.rows.first { it.id == thisPhone }
        assertEquals("Pixel", mine.label?.name)
        assertEquals(DeviceNameSeal.Kind.Android, mine.label?.kind)
        assertEquals(DeviceKind.Android, mine.kind)
    }

    @Test
    fun renameKeepsTheKindAndFallsBackWhenThereIsNoLabel() = runTest(main.dispatcher) {
        val harness = harness()
        harness.controller.refresh()

        assertNull(harness.controller.rename(tablet, "Kitchen"))
        assertEquals(DeviceNameSeal.Kind.IPad, opened(tablet, harness).kind)
        assertTrue(opened(tablet, harness).custom)
        assertEquals("Kitchen", opened(tablet, harness).name)

        assertNull(harness.controller.rename(oldPhone, "Old phone"))
        assertEquals(DeviceNameSeal.Kind.Other, opened(oldPhone, harness).kind)
        assertTrue(opened(oldPhone, harness).custom)

        val mine = harness.devices.indexOfFirst { it.id == thisPhone }
        harness.devices[mine] = harness.devices[mine].copy(sealedName = null)
        harness.controller.refresh()
        assertNull(harness.controller.rename(thisPhone, "Pocket"))
        assertEquals(DeviceNameSeal.Kind.Android, opened(thisPhone, harness).kind)
        assertTrue(opened(thisPhone, harness).custom)

        val puts = harness.named.size
        assertEquals(DevicesController.ENTER_A_NAME, harness.controller.rename(laptop, "   "))
        assertEquals(puts, harness.named.size)

        harness.unlockedUserId = null
        assertEquals(DevicesController.UNLOCK_TO_RENAME, harness.controller.rename(laptop, "Desk"))
        assertEquals(puts, harness.named.size)

        harness.session = null
        assertEquals(DevicesController.UNLOCK_TO_RENAME, harness.controller.rename(laptop, "Desk"))
        assertEquals(puts, harness.named.size)
    }

    @Test
    fun aRenameServerErrorIsTheServerMessage() = runTest(main.dispatcher) {
        val harness = harness()
        harness.controller.refresh()
        harness.putError = ApiError.Server(ErrorCodes.RATE_LIMITED, "Slow down.", 429)
        assertEquals("Slow down.", harness.controller.rename(tablet, "Kitchen"))
        assertTrue(harness.named.isEmpty())
    }

    @Test
    fun aMissingDeviceIsAlreadyRemovedAndThisPhoneIsNotRemoved() = runTest(main.dispatcher) {
        val harness = harness()
        harness.controller.refresh()
        harness.revokeError = { id ->
            if (id == laptop) ApiError.Server(ErrorCodes.NOT_FOUND, "Not found.", 404) else null
        }
        assertEquals(RemoveOutcome.AlreadyRemoved, harness.controller.remove(laptop))
        assertTrue(harness.revoked.isEmpty())
        assertTrue(harness.controller.state.value.rows.none { it.id == laptop })
        assertNull(harness.controller.state.value.error)

        val note = harness.controller.remove(thisPhone)
        assertEquals(
            "To remove this phone from your account, use Log Out in Settings. It also erases everything Shroud keeps here.",
            (note as RemoveOutcome.Failed).message,
        )
        assertTrue(harness.revoked.isEmpty())
        assertTrue(harness.controller.state.value.rows.any { it.id == thisPhone })
    }

    @Test
    fun removeAllOthersCountsA404AndKeepsTheSentenceAfterReload() = runTest(main.dispatcher) {
        val harness = harness()
        harness.controller.refresh()
        harness.revokeError = { id ->
            when (id) {
                laptop -> ApiError.Server(ErrorCodes.INTERNAL_ERROR, "Something went wrong.", 500)
                oldPhone -> ApiError.Server(ErrorCodes.NOT_FOUND, "Gone.", 404)
                else -> null
            }
        }
        val outcome = harness.controller.removeAllOthers()
        assertEquals(RemoveOutcome.Partial(removed = 2, failed = 1), outcome)
        assertEquals(listOf(tablet), harness.revoked)
        assertEquals(
            "1 of 3 devices could not be removed. Something went wrong.",
            harness.controller.state.value.error,
        )
        assertEquals(listOf(thisPhone, laptop), harness.controller.state.value.rows.map { it.id })
    }

    @Test
    fun oneFailedRemovalUsesTheSingularSentence() = runTest(main.dispatcher) {
        val harness = harness()
        harness.devices.removeAll { it.id != thisPhone && it.id != laptop }
        harness.controller.refresh()
        harness.revokeError = { ApiError.Server(ErrorCodes.INTERNAL_ERROR, "Something went wrong.", 500) }
        val outcome = harness.controller.removeAllOthers()
        assertEquals(
            RemoveOutcome.Failed("The device could not be removed. Something went wrong."),
            outcome,
        )
        assertTrue(harness.controller.state.value.rows.any { it.id == laptop })
    }

    @Test
    fun removeAllOthersCountsEvery404AsRemoved() = runTest(main.dispatcher) {
        val harness = harness()
        harness.controller.refresh()
        harness.revokeError = { ApiError.Server(ErrorCodes.NOT_FOUND, "Gone.", 404) }
        assertEquals(RemoveOutcome.Removed, harness.controller.removeAllOthers())
        assertEquals(listOf(thisPhone), harness.controller.state.value.rows.map { it.id })
        assertNull(harness.controller.state.value.error)
    }

    @Test
    fun clearDropsRowsAndAnOlderRefreshDoesNotRestoreThem() = runTest(main.dispatcher) {
        val harness = harness()
        harness.controller.refresh()
        val gate = CompletableDeferred<Unit>()
        harness.listGate = gate
        val older = launch { harness.controller.refresh() }
        assertEquals(2, harness.listCalls)
        harness.controller.clear()
        assertEquals(DevicesState(), harness.controller.state.value)

        val newer = launch { harness.controller.refresh() }
        assertEquals(3, harness.listCalls)
        gate.complete(Unit)
        older.join()
        newer.join()
        assertTrue(harness.controller.state.value.hasLoaded)
        assertEquals(4, harness.controller.state.value.rows.size)
        assertNull(harness.controller.state.value.error)
    }

    @Test
    fun aFailedRefreshThatStartedBeforeClearDoesNotSetTheError() = runTest(main.dispatcher) {
        val harness = harness()
        harness.controller.refresh()
        val gate = CompletableDeferred<Unit>()
        harness.listGate = gate
        val older = launch { harness.controller.refresh() }
        harness.listError = ApiError.Server(ErrorCodes.INTERNAL_ERROR, "Could not load devices.", 500)
        harness.controller.clear()
        gate.complete(Unit)
        older.join()
        assertEquals(DevicesState(), harness.controller.state.value)
    }

    @Test
    fun clearIsSafeOffTheMainThreadAndDropsTheSealedNameCache() = runTest(main.dispatcher) {
        val harness = harness()
        harness.controller.refresh()
        withContext(Dispatchers.Default) { harness.controller.clear() }
        assertEquals(DevicesState(), harness.controller.state.value)
        assertNull(harness.controller.rename(tablet, "Kitchen"))
        assertEquals(DeviceNameSeal.Kind.Other, opened(tablet, harness).kind)
    }

    @Test
    fun aRemovalThatStartedBeforeClearDoesNotRestoreTheList() = runTest(main.dispatcher) {
        val harness = harness()
        harness.controller.refresh()
        val gate = CompletableDeferred<Unit>()
        harness.listGate = gate
        val removing = launch { harness.controller.remove(laptop) }
        assertEquals(listOf(laptop), harness.revoked)
        assertEquals(2, harness.listCalls)
        harness.controller.clear()
        gate.complete(Unit)
        removing.join()
        assertEquals(DevicesState(), harness.controller.state.value)
    }

    @Test
    fun removeAllWithNothingElseToRemoveDoesNotCallTheServer() = runTest(main.dispatcher) {
        val harness = harness()
        harness.devices.removeAll { it.id != thisPhone }
        harness.controller.refresh()
        assertEquals(RemoveOutcome.Removed, harness.controller.removeAllOthers())
        assertTrue(harness.revoked.isEmpty())
    }

    @Test
    fun signedOutRemovalsDoNotCallTheServer() = runTest(main.dispatcher) {
        val harness = harness()
        harness.controller.refresh()
        harness.session = null
        assertEquals(RemoveOutcome.Failed(DevicesController.SIGN_IN), harness.controller.remove(laptop))
        assertEquals(RemoveOutcome.Failed(DevicesController.SIGN_IN), harness.controller.removeAllOthers())
        assertEquals(DevicesController.SIGN_IN, harness.controller.state.value.error)
        assertEquals(4, harness.controller.state.value.rows.size)
        assertTrue(harness.revoked.isEmpty())
    }

    @Test
    fun aRemovalWaitsForTheLoadAlreadyInFlightThenLoadsAgain() = runTest(main.dispatcher) {
        val harness = harness()
        val gate = CompletableDeferred<Unit>()
        harness.listGate = gate
        val loading = launch { harness.controller.refresh() }
        val removing = launch { harness.controller.remove(laptop) }
        assertEquals(1, harness.listCalls)
        assertEquals(listOf(laptop), harness.revoked)
        gate.complete(Unit)
        loading.join()
        removing.join()
        assertEquals(2, harness.listCalls)
        assertTrue(harness.controller.state.value.rows.none { it.id == laptop })
    }

    private fun opened(id: UUID, harness: DevicesHarness): DeviceNameSeal.Label {
        val sealed = harness.devices.first { it.id == id }.sealedName
        return checkNotNull(DeviceNameSeal.open(sealed, id, history))
    }

    private fun session(deviceId: UUID = thisPhone) = Session("tok", userId, "noah", null, deviceId.toString())

    private fun device(id: UUID, created: String, seen: String?, current: Boolean, sealed: String? = null) = LinkedDeviceDto(
        id = id,
        sealedName = sealed,
        createdAt = Instant.parse(created),
        lastSeenAt = seen?.let(Instant::parse),
        isCurrent = current,
    )

    private fun harness(
        session: Session? = session(),
        unlocked: String? = userId,
        deviceId: UUID = thisPhone,
        flagCurrent: Boolean = true,
    ): DevicesHarness {
        val rows = mutableListOf(
            device(laptop, "2026-09-21T00:00:00Z", "2026-09-23T00:00:00Z", current = false, sealed = seal(laptop, "Mac", DeviceNameSeal.Kind.Web)),
            device(thisPhone, "2026-09-20T00:00:00Z", "2026-10-01T00:00:00Z", current = flagCurrent, sealed = seal(thisPhone, "Pixel", DeviceNameSeal.Kind.Android)),
            device(oldPhone, "2026-09-25T00:00:00Z", null, current = false),
            device(tablet, "2026-09-22T00:00:00Z", "2026-09-30T00:00:00Z", current = false, sealed = seal(tablet, "iPad", DeviceNameSeal.Kind.IPad)),
        )
        return DevicesHarness(session ?: this.session(deviceId), unlocked, rows, history).also {
            if (session == null) it.session = null
            if (deviceId != thisPhone) it.session = this.session(deviceId)
        }
    }

    private fun seal(id: UUID, name: String, kind: DeviceNameSeal.Kind) =
        DeviceNameSeal.seal(DeviceNameSeal.Label(name, kind), id, history)
}

private class DevicesHarness(
    var session: Session?,
    var unlockedUserId: String?,
    val devices: MutableList<LinkedDeviceDto>,
    private val history: ByteArray,
) {
    val revoked = mutableListOf<UUID>()
    val named = mutableListOf<UUID>()
    var listCalls = 0
    var listGate: CompletableDeferred<Unit>? = null
    var listError: Exception? = null
    var putError: Exception? = null
    var revokeError: (UUID) -> Exception? = { null }

    val controller = ShroudDevicesController(
        listDevices = {
            listCalls++
            listGate?.await()
            listError?.let { throw it }
            devices.toList()
        },
        revokeDevice = { _, id ->
            revokeError(id)?.let { error ->
                if ((error as? ApiError)?.isNotFound == true) devices.removeAll { it.id == id }
                throw error
            }
            revoked += id
            devices.removeAll { it.id == id }
        },
        putDeviceName = { _, id, sealedName ->
            putError?.let { throw it }
            named += id
            val index = devices.indexOfFirst { it.id == id }
            if (index >= 0) devices[index] = devices[index].copy(sealedName = sealedName)
        },
        session = { session },
        unlockedUserId = { unlockedUserId },
        openName = { sealed, id -> DeviceNameSeal.open(sealed, id, history) },
        sealName = { label, id -> DeviceNameSeal.seal(label, id, history) },
        deviceNoun = { "phone" },
    )
}
