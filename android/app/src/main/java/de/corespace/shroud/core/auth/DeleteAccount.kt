package de.corespace.shroud.core.auth

import de.corespace.shroud.core.net.ApiError
import de.corespace.shroud.core.net.ErrorCodes
import de.corespace.shroud.core.net.ShroudApi
import kotlinx.coroutines.CancellationException

/** What `DELETE /auth/account` means for the screen (account-deletion plan §3.2). */
enum class DeleteAccountAnswer {
    /** `204`. The wipe has been started. */
    Deleted,

    /** `401 INVALID_CREDENTIALS`. Stay; the password stays selected. */
    WrongPassword,

    /** `401 DEVICE_REMOVED`, any reason. The wipe has been started as [WipeReason.AccountDeleted]. */
    RemovedAsDeleted,

    /** `429`. */
    RateLimited,

    /** Transport, timeout, `5xx`, or any other refusal. Nothing was deleted. */
    Unreachable,
}

/** The §3.2 table. [error] is null when the call returned. */
object DeleteAccountMapping {
    fun of(error: Throwable?): DeleteAccountAnswer = when (error) {
        null -> DeleteAccountAnswer.Deleted
        is ApiError.Transport -> DeleteAccountAnswer.Unreachable
        is ApiError.Server -> when {
            error.status == 401 && error.code == ErrorCodes.INVALID_CREDENTIALS -> DeleteAccountAnswer.WrongPassword
            error.status == 401 && error.code == ErrorCodes.DEVICE_REMOVED -> DeleteAccountAnswer.RemovedAsDeleted
            error.status == 429 -> DeleteAccountAnswer.RateLimited
            error.status >= 500 -> DeleteAccountAnswer.Unreachable
            else -> DeleteAccountAnswer.Unreachable
        }
        else -> DeleteAccountAnswer.Unreachable
    }
}

/** What the delete-account screen calls. Screens never call [ShroudApi] themselves. */
fun interface AccountDeleter {
    suspend fun delete(password: String): DeleteAccountAnswer
}

/**
 * `DELETE /auth/account`, then the §3.2 answer. A deletion or a `DEVICE_REMOVED` on this call starts
 * [WipeReason.AccountDeleted]. The password is the argument of [delete] and is not kept here.
 */
class AccountDeletion(
    private val api: ShroudApi,
    private val token: () -> String?,
    private val startWipe: (WipeReason) -> Unit,
) : AccountDeleter {
    override suspend fun delete(password: String): DeleteAccountAnswer {
        val current = token() ?: return DeleteAccountAnswer.Unreachable
        return try {
            api.deleteAccount(current, password)
            startWipe(WipeReason.AccountDeleted)
            DeleteAccountAnswer.Deleted
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val answer = DeleteAccountMapping.of(e)
            if (answer == DeleteAccountAnswer.RemovedAsDeleted) startWipe(WipeReason.AccountDeleted)
            answer
        }
    }
}
