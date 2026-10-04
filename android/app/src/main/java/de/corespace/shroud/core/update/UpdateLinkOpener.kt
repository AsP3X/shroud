package de.corespace.shroud.core.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri

/**
 * Opens the operator's update link ([ClientUpdate.updateUrl]) with a plain `ACTION_VIEW`, not a
 * Custom Tab ([de.corespace.shroud.core.links.LinkOpener]): an F-Droid or Obtainium page should
 * reach the store app that claims it, and an APK download belongs in the user's own browser. Like
 * chat links it sends no referrer. Only `http`/`https` open.
 */
object UpdateLinkOpener {
    /** Opens [url]; false when it is not a web link or nothing on the phone can open it. */
    fun open(context: Context, url: String): Boolean {
        val uri = url.trim().toUri().normalizeScheme()
        if (uri.scheme != "http" && uri.scheme != "https") return false
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .putExtra(Intent.EXTRA_REFERRER, Uri.EMPTY)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
