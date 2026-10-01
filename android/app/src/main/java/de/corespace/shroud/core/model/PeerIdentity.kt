package de.corespace.shroud.core.model

import java.util.UUID

// Peer key pinning (TOFU) as the engines and screens see it (plan §1.7.5, §1.7.8; contacts §4).
// Seam published by W1-INT; PeerIdentityController (W2-CONTACTS) produces these.

/** A pinned contact's key changed (`IdentitySafetyNumber.swift:45-48`): sends stop until the user accepts it. */
data class PeerIdentityChange(val previousKey: Bytes, val currentKey: Bytes)

/**
 * Thrown by `PeerIdentities.publicKeyForSending` while a change is pending. The message is the
 * user-facing text (`SessionController.userMessage`, `SessionController.swift:228-230`).
 */
class PeerIdentityChangedException : Exception(MESSAGE) {
    companion object {
        const val MESSAGE = "This contact's encryption key changed. Verify their safety number before sending."
    }
}

/** What happened to a peer's pinned key; calls refresh their secrets on it (plan C29). */
sealed interface PeerIdentityEvent {
    val peer: UUID

    /** First key seen and pinned. */
    data class Pinned(override val peer: UUID) : PeerIdentityEvent

    /** The server now hands out a different key than the pinned one. */
    data class KeyChanged(override val peer: UUID) : PeerIdentityEvent

    /** The user accepted the new key: repinned, verification cleared, ratchet session dropped. */
    data class KeyAccepted(override val peer: UUID) : PeerIdentityEvent
}
