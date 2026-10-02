package de.corespace.shroud.core.calls.system

import android.content.Intent
import de.corespace.shroud.core.model.Ids
import java.util.UUID

/**
 * Extras on every ring and answer [android.app.PendingIntent] that opens the call screen
 * (K8). The component is `de.corespace.shroud.ui.calls.CallActivity`.
 */
object CallIntents {
    const val EXTRA_CALL_ID = "call_id"
    const val EXTRA_ACTION = "action"   // "show" | "answer"

    /** The call id and `"show"` or `"answer"`, or null when either extra is missing or malformed. */
    fun parse(intent: Intent): Pair<UUID, String>? {
        val action = intent.getStringExtra(EXTRA_ACTION)
        if (action != "show" && action != "answer") return null
        val id = Ids.parse(intent.getStringExtra(EXTRA_CALL_ID)) ?: return null
        return id to action
    }
}

interface CallScreenHooks {
    /** Starts the phoneCall FGS if an earlier start was refused. */
    fun onCallScreenShown(callId: UUID)
    fun onCallScreenHidden()
}
