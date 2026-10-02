package de.corespace.shroud.core.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.os.UserManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.corespace.shroud.R
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.core.auth.DeviceRemovalWake
import de.corespace.shroud.core.auth.WakeResult
import de.corespace.shroud.core.notifications.NotificationChannels
import de.corespace.shroud.core.notifications.SystemNotifier
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
    /**
     * API 30 and below run expedited work as a foreground service and throw from here when the
     * worker does not supply one. The confirmation never reaches `GET /auth/me` in that case.
     * API 31 and above do not call this.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(NotificationChannels.BACKGROUND_CONNECTION) == null) {
            val channel = NotificationChannel(
                NotificationChannels.BACKGROUND_CONNECTION,
                "Background connection",
                NotificationManager.IMPORTANCE_MIN,
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(context, NotificationChannels.BACKGROUND_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_shroud)
            .setContentTitle(SystemNotifier.APP_TITLE)
            .setContentText(CHECKING_TEXT)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

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
        const val NOTIFICATION_ID = 7102
        const val CHECKING_TEXT = "Checking this device"

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
