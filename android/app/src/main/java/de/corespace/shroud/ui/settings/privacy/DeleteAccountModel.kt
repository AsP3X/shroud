package de.corespace.shroud.ui.settings.privacy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import de.corespace.shroud.core.auth.AccountDeleter
import de.corespace.shroud.core.auth.DeleteAccountAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The red line under the password card. */
internal enum class DeleteAccountFieldError(val text: String) {
    WrongPassword(DeleteAccountCopy.WRONG_PASSWORD),
    RateLimited(DeleteAccountCopy.RATE_LIMITED),
    Unreachable(DeleteAccountCopy.UNREACHABLE),
}

/**
 * Password and the §3.2 answer for [DeleteAccountScreen]. Calls [AccountDeleter] only.
 * The password stays in the field on a wrong password, a rate limit, or an unreachable server,
 * and is cleared after a deletion and when the screen leaves.
 */
internal class DeleteAccountModel(
    private val deleter: AccountDeleter,
    private val scope: CoroutineScope,
) {
    var password by mutableStateOf(TextFieldValue(""))
        private set

    var revealed by mutableStateOf(false)
        private set

    var submitting by mutableStateOf(false)
        private set

    var error by mutableStateOf<DeleteAccountFieldError?>(null)
        private set

    /** Any non-empty value enables the button, including spaces. Disabled while the request runs. */
    val canSubmit: Boolean get() = password.text.isNotEmpty() && !submitting

    fun onPasswordChange(value: TextFieldValue) {
        if (submitting) return
        if (value.text != password.text) error = null
        password = value
    }

    fun toggleRevealed() {
        if (!submitting) revealed = !revealed
    }

    fun submit() {
        if (!canSubmit) return
        val typed = password.text
        submitting = true
        error = null
        scope.launch {
            val answer = try {
                deleter.delete(typed)
            } catch (e: CancellationException) {
                submitting = false
                throw e
            }
            when (answer) {
                DeleteAccountAnswer.Deleted, DeleteAccountAnswer.RemovedAsDeleted -> clear()
                DeleteAccountAnswer.WrongPassword -> {
                    password = password.copy(selection = TextRange(0, password.text.length))
                    error = DeleteAccountFieldError.WrongPassword
                    submitting = false
                }
                DeleteAccountAnswer.RateLimited -> {
                    error = DeleteAccountFieldError.RateLimited
                    submitting = false
                }
                DeleteAccountAnswer.Unreachable -> {
                    error = DeleteAccountFieldError.Unreachable
                    submitting = false
                }
            }
        }
    }

    fun clear() {
        password = TextFieldValue("")
        revealed = false
        error = null
        submitting = false
    }
}
