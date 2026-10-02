package de.corespace.shroud.core.push

import android.content.Context
import androidx.core.os.UserManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.auth.DeviceRemovalWake
import de.corespace.shroud.core.auth.WakeResult
import kotlinx.coroutines.CancellationException

/**
 * Expedited confirmation of a `device_removed` push (plan §1.7.10, settings-lock §14.6).
 * The push never deletes on its own: [DeviceRemovalWake] asks `GET /auth/me` and wipes only on
 * `DEVICE_REMOVED`. This work does not carry [de.corespace.shroud.core.auth.DeviceDataWipe.ACCOUNT_WORK_TAG],
 * which the wipe cancels.
 *
 * [wakeOverride] is the test seam. Production calls [DeviceRemovalWake.handle].
 */
class DeviceRemovalWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val outcome = try {
            val override = wakeOverride
            if (override != null) {
                override()
            } else {
                val app = applicationContext as? ShroudApplication ?: return Result.retry()
                if (!UserManagerCompat.isUserUnlocked(app)) return Result.retry()
                DeviceRemovalWake.handle(app.container)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.retry()
        }
        return when (outcome) {
            WakeResult.Failed -> Result.retry()
            WakeResult.NoData, WakeResult.NewData -> Result.success()
        }
    }

    companion object {
        const val UNIQUE_NAME = "shroud.device-removal"

        @Volatile
        var wakeOverride: (suspend () -> WakeResult)? = null

        fun enqueue(context: Context) {
            runCatching {
                val request = try {
                    OneTimeWorkRequestBuilder<DeviceRemovalWorker>()
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                } catch (_: Exception) {
                    OneTimeWorkRequestBuilder<DeviceRemovalWorker>().build()
                }
                WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
            }
        }
    }
}
