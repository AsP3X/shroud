package de.corespace.shroud.core.model

// Results and list states the engines publish to screens (plan §1.7.5, messaging-core §2.4).
// Seam published by W1-INT.

/** What became of a chat delete (`MessagingController.swift:1956-1965`). */
sealed interface ChatDeleteOutcome {
    /** Gone from this account only. */
    data object ClearedForMe : ChatDeleteOutcome

    /** Gone for both people. */
    data object ClearedForBoth : ChatDeleteOutcome

    /** The peer does not allow chat deletes for both: only our own messages were unsent. */
    data object UnsentForPeer : ChatDeleteOutcome

    data class Failed(val message: String) : ChatDeleteOutcome
}

/** What became of adding a contact (`MessagingController.swift:886-892`). */
sealed interface AddContactOutcome {
    /** A request went out and waits for the other person. */
    data class Requested(val username: String) : AddContactOutcome

    /** They had asked us already: now contacts. */
    data class Added(val username: String) : AddContactOutcome

    data class Failed(val message: String) : AddContactOutcome
}

/** The chat list's load state (shell-chats §8): skeleton, cached list, server list, error. */
data class ListStatus(
    val isLoadingChats: Boolean = false,
    val hasLoadedChats: Boolean = false,
    val hasLoadedServerChats: Boolean = false,
    val chatsError: String? = null,
)

/** The contacts list's load state (contacts §2). */
data class ContactsListState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
)
