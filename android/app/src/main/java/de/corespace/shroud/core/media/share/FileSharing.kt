package de.corespace.shroud.core.media.share

import java.util.UUID

/** What [FileSharing.openTarget] found: a grant to hand to `ACTION_VIEW`, or why Shroud won't open it. */
sealed interface FileOpenOutcome {
    /** [extension] (lowercased) names the type in "No app on this phone can open .{ext} files." */
    data class Ready(val target: ShareTarget, val extension: String) : FileOpenOutcome

    /** The sentence to show: a content mismatch (§4), an APK (never opened here), a file not on this phone. */
    data class Refused(val message: String) : FileOpenOutcome
}

/**
 * Open, share and Save to Downloads of a received or sent file (docs/file-sharing.md §6–§8). The
 * file stays sealed in the SHRM1 cache: grants are `content://<app>.media/<random>` URIs that
 * [DecryptedMediaProvider] serves segment by segment from a reader, with the cleaned name as
 * `DISPLAY_NAME` and the type table's MIME; Save to Downloads streams it into `MediaStore.Downloads`
 * (no permission on API 29+). [MediaSharing.revokeAll] drops these grants too (lock, wipe). The
 * §6 warning dialog is the caller's, before any of these; nothing here asks or remembers it.
 *
 * [fileName] is the bubble's name; it is cleaned again here and decides the type (§4, §5).
 */
interface FileSharing {
    /**
     * A grant for `ACTION_VIEW` after §4's content check of the first bytes. Refused — with the
     * sentence to show — when the type cannot be opened on Android (an APK), the bytes do not match
     * the extension, or the file is not on this phone.
     */
    suspend fun openTarget(messageId: UUID, fileName: String): FileOpenOutcome

    /**
     * §4's check alone, for Shroud's own PDF viewer (§10.2), which reads the file without a grant:
     * null when it may open, else the sentence [openTarget] would refuse with.
     */
    suspend fun checkBeforeOpening(messageId: UUID, fileName: String): String?

    /** A grant for the share sheet (no content check: sharing stays possible, §4); null when the file is not on this phone. */
    suspend fun fileShareTarget(messageId: UUID, fileName: String): ShareTarget?

    /** Streams the file into `Download/Shroud/` (`IS_PENDING` until it is complete). A sentence in [SaveOutcome.Failed]; never throws. */
    suspend fun saveToDownloads(messageId: UUID, fileName: String): SaveOutcome
}
