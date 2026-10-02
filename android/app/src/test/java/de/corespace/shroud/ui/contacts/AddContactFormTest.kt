package de.corespace.shroud.ui.contacts

import de.corespace.shroud.core.model.AddContactOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Add Contact's form (iOS `AddContactSheet.swift:12-15, 56-59, 82-103`; contacts §5.4, §5.10):
 * when Add is live, what each outcome says, and that an App Link's pre-fill never sends by itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddContactFormTest {
    @Test
    fun addIsLiveOnlyWithTextAndNothingInFlight() {
        val form = AddContactForm(prefill = null)
        assertFalse(form.canSubmit)
        form.text = " \n\t "
        assertFalse("whitespace and newlines only", form.canSubmit)
        form.text = "  jane  "
        assertTrue(form.canSubmit)
        assertEquals("Add", ContactsCopy.addButtonTitle(form.isAdding))
        assertFalse(ContactsCopy.canSubmit("jane", isAdding = true))
        assertEquals("Adding…", ContactsCopy.addButtonTitle(isAdding = true))
    }

    @Test
    fun anAppLinkPrefillsTheFieldAndWaitsForTheTap() = runTest {
        var sent = 0
        val form = AddContactForm(prefill = "https://shroud.corespace.de/u/ABCD234567")
        assertEquals("https://shroud.corespace.de/u/ABCD234567", form.text)
        assertTrue(form.canSubmit)
        // Nothing has been sent: the form only sends from submit(), which the Add tap calls.
        assertEquals(0, sent)
        assertFalse(form.isAdding)
        val result = form.submit(form.text) {
            sent++
            AddContactOutcome.Requested("jane")
        }
        assertEquals(1, sent)
        assertEquals(AddContactForm.Submission.Done("Request sent to jane"), result)
    }

    @Test
    fun aRequestAndAMutualAcceptAreConfirmedForTheTab() = runTest {
        val form = AddContactForm(prefill = null)
        assertEquals(AddContactForm.Submission.Done("Request sent to jane"), form.submit("jane") { AddContactOutcome.Requested("jane") })
        assertEquals(AddContactForm.Submission.Done("noah added"), form.submit("noah") { AddContactOutcome.Added("noah") })
        assertNull(form.error)
    }

    @Test
    fun aFailureStaysInTheSheetAndIsClearedByTheNextTry() = runTest {
        val form = AddContactForm(prefill = null)
        val failed = form.submit("nobody") { AddContactOutcome.Failed("User not found.") }
        assertEquals(AddContactForm.Submission.Failed("User not found."), failed)
        assertEquals("User not found.", form.error)

        val gate = CompletableDeferred<AddContactOutcome>()
        val retry = launch(UnconfinedTestDispatcher(testScheduler)) { form.submit("jane") { gate.await() } }
        // While the retry is on the wire: no stale error, Add is off and says "Adding…".
        assertNull(form.error)
        assertTrue(form.isAdding)
        assertFalse(form.canSubmit)
        gate.complete(AddContactOutcome.Requested("jane"))
        retry.join()
        assertFalse(form.isAdding)
    }

    @Test
    fun aSecondSubmitWhileOneRunsIsIgnored() = runTest {
        val form = AddContactForm(prefill = null)
        val gate = CompletableDeferred<AddContactOutcome>()
        var calls = 0
        val first = launch(UnconfinedTestDispatcher(testScheduler)) {
            form.submit("jane") {
                calls++
                gate.await()
            }
        }
        // A scanned code landing while the typed one is on the wire.
        assertEquals(AddContactForm.Submission.Ignored, form.submit("https://shroud.corespace.de/u/ABCD234567") { error("not sent") })
        gate.complete(AddContactOutcome.Requested("jane"))
        first.join()
        assertEquals(1, calls)
    }

    @Test
    fun theSheetCopyIsIosVerbatim() {
        assertEquals("Add Contact", ContactsCopy.ADD_CONTACT_TITLE)
        assertEquals("Scan QR code", ContactsCopy.SCAN_QR_CODE)
        assertEquals("Scan your contact’s Shroud QR code.", ContactsCopy.SCAN_FOOTER)
        assertEquals("Code, link, or username", ContactsCopy.FIELD_PLACEHOLDER)
        assertEquals("Paste a share link, enter their short share code, username, or user ID.", ContactsCopy.FIELD_FOOTER)
        assertNull(ContactsCopy.confirmation(AddContactOutcome.Failed("x")))
    }
}
