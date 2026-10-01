package de.corespace.shroud.core.auth

import android.app.Notification
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import java.io.File

/**
 * [PrefsAccess] on the app's SharedPreferences: the files under `shared_prefs/` plus [known] (a file
 * written with `apply()` may not be on disk yet).
 */
class AndroidPrefsAccess(private val context: Context, private val known: Collection<String>) : PrefsAccess {
    override fun fileNames(): Set<String> {
        val onDisk = File(context.dataDir, "shared_prefs").listFiles()
            ?.filter { it.isFile && it.name.endsWith(XML) }
            ?.map { it.name.removeSuffix(XML) }
            .orEmpty()
        return (onDisk + known).toSet()
    }

    override fun open(name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private companion object {
        const val XML = ".xml"
    }
}

/**
 * [SystemWipe] on the device (settings-lock §14.3 Settings row; iOS `touchesSystemState`,
 * `DeviceDataWipe.swift:146, 174-183, 206-213`).
 *
 * - Notifications: `cancelAll()`; the launcher badge follows them. A foreground service's own
 *   notification (the background connection, W3-PUSH) cannot be cancelled while it runs and names
 *   no one, so the leftovers check skips it; the wipe stops that service when it forgets push.
 * - Clipboard: cleared when the primary clip is our phrase (label `"Shroud encryption phrase"`,
 *   `PhraseClipboard.kt`). Android 10+ hides the clip from an app in the background, so only a wipe
 *   in the foreground can see it there.
 * - WebView cookies: the app never loads a WebView (links open in Custom Tabs); if a `app_webview`
 *   directory exists anyway, the Settings sweep of `app_*` deletes its cookie database. Loading
 *   `CookieManager` here would start a WebView that holds those files open.
 * - WorkManager: only jobs tagged [DeviceDataWipe.ACCOUNT_WORK_TAG] are cancelled, then finished
 *   jobs pruned, and only if WorkManager was ever used; its database is on the keep-list.
 */
class AndroidSystemWipe(
    private val context: Context,
    private val connectionPool: () -> ConnectionPool?,
) : SystemWipe {
    override fun evictNetworkConnections() {
        runCatching { connectionPool()?.evictAll() }
    }

    override fun cancelAccountWork() {
        val used = File(context.noBackupFilesDir, WORK_DATABASE).exists() || context.getDatabasePath(WORK_DATABASE).exists()
        if (!used) return
        runCatching {
            WorkManager.getInstance(context).apply {
                cancelAllWorkByTag(DeviceDataWipe.ACCOUNT_WORK_TAG)
                pruneWork()
            }
        }
    }

    override suspend fun clearSystemState() = withContext(Dispatchers.Main) {
        runCatching { notifications().cancelAll() }
        runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            if (clipboard?.primaryClipDescription?.label?.toString() == PHRASE_CLIP_LABEL) clipboard.clearPrimaryClip()
        }
        Unit
    }

    override suspend fun leftovers(): List<DeviceDataWipe.Leftover> = withContext(Dispatchers.Main) {
        val active = runCatching { notifications().activeNotifications.toList() }.getOrDefault(emptyList())
            .filter { it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE == 0 }
        if (active.isEmpty()) emptyList() else listOf(DeviceDataWipe.Leftover(WipeStep.Settings, DeviceDataWipe.LABEL_NOTIFICATIONS))
    }

    private fun notifications(): NotificationManager = context.getSystemService(NotificationManager::class.java)

    private companion object {
        /** WorkManager's database (in `no_backup/` since 2.6; `databases/` before). */
        const val WORK_DATABASE = "androidx.work.workdb"

        /** `PhraseClipboard.LABEL` (`ui/onboarding/PhraseClipboard.kt:23`). */
        const val PHRASE_CLIP_LABEL = "Shroud encryption phrase"
    }
}
