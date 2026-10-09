package de.corespace.shroud.ui.settings.privacy

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import de.corespace.shroud.core.auth.AccountDeleter
import de.corespace.shroud.core.auth.DeleteAccountAnswer
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The field keeps a wrong password selected, and drops it once the account is gone. */
class DeleteAccountModelTest {
    @Test
    fun emptyAndBlankSpace() = runTest {
        var calls = 0
        val model = DeleteAccountModel(AccountDeleter { calls++; DeleteAccountAnswer.Deleted }, this)
        assertFalse(model.canSubmit)
        model.submit()
        advanceUntilIdle()
        assertEquals(0, calls)
        model.onPasswordChange(TextFieldValue(" "))
        assertTrue(model.canSubmit)
    }

    @Test
    fun wrongPasswordKeepsTheTextSelected() = runTest {
        val model = DeleteAccountModel(AccountDeleter { DeleteAccountAnswer.WrongPassword }, this)
        model.onPasswordChange(TextFieldValue("secret"))
        model.submit()
        advanceUntilIdle()
        assertEquals("secret", model.password.text)
        assertEquals(TextRange(0, "secret".length), model.password.selection)
        assertEquals(DeleteAccountFieldError.WrongPassword, model.error)
        assertFalse(model.submitting)
        assertTrue(model.canSubmit)
    }

    @Test
    fun rateLimitAndUnreachableKeepThePasswordUnselected() = runTest {
        val limited = DeleteAccountModel(AccountDeleter { DeleteAccountAnswer.RateLimited }, this)
        limited.onPasswordChange(TextFieldValue("secret"))
        limited.submit()
        advanceUntilIdle()
        assertEquals("secret", limited.password.text)
        assertEquals(TextRange.Zero, limited.password.selection)
        assertEquals(DeleteAccountFieldError.RateLimited, limited.error)
        assertFalse(limited.submitting)

        val offline = DeleteAccountModel(AccountDeleter { DeleteAccountAnswer.Unreachable }, this)
        offline.onPasswordChange(TextFieldValue("secret", TextRange(6)))
        offline.submit()
        advanceUntilIdle()
        assertEquals("secret", offline.password.text)
        assertEquals(TextRange(6), offline.password.selection)
        assertEquals(DeleteAccountFieldError.Unreachable, offline.error)
    }

    @Test
    fun successClearsThePassword() = runTest {
        val model = DeleteAccountModel(AccountDeleter { DeleteAccountAnswer.RemovedAsDeleted }, this)
        model.onPasswordChange(TextFieldValue("secret"))
        model.submit()
        advanceUntilIdle()
        assertEquals("", model.password.text)
        assertNull(model.error)
        assertFalse(model.submitting)
        model.onPasswordChange(TextFieldValue("again"))
        model.clear()
        assertEquals("", model.password.text)
    }
}
