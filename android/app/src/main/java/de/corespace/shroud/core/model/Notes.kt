package de.corespace.shroud.core.model

import java.util.UUID

// Notes to me (Saved Messages): a chat with yourself that never leaves the phone unless synced as a
// note to your own id (messaging-core §11.4, §23). Seam published by W1-INT (plan §1.7.5).

/**
 * The thread key Notes live under — never the user's own id (`LocalMessageStore.swift:15`,
 * `MessagingController.swift:16`). The last six bytes spell "notes!" in ASCII. Same value on iOS.
 */
val NOTES_PEER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-6e6f74657321")

/** The chat's title everywhere it is listed (`MessagingController.notesDisplayName`). */
const val NOTES_DISPLAY_NAME = "Notes to me"
