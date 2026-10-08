package de.corespace.shroud.core.crypto

/**
 * Errors of the crypto layer (iOS `MessageCrypto.CryptoError`, `ios/shroud/Services/Crypto/MessageCrypto.swift:53-60`;
 * crypto spec §1.5). Callers only tell [UnauthenticatedSender] apart; everything else renders as
 * "[Unable to decrypt]" (`ios/shroud/Services/Messaging/MessageDecoder.swift:283`); [Locked] is Android/web-only.
 *
 * The cases are singletons without a stack trace or suppressed list (one shared instance must not
 * collect state across throws). Library exceptions (`AEADBadTagException`,
 * `SerializationException`, BouncyCastle's `IllegalStateException`) are mapped onto these and never
 * passed on, so no message can carry key or plaintext bytes.
 */
sealed class CryptoError(msg: String) : Exception(msg, null, false, false) {
    /** A public or private key that is not 32 bytes, or a low-order X25519 point. */
    object InvalidPeerKey : CryptoError("invalid peer key")

    /** AES-GCM could not seal (bad key or nonce size, provider failure). */
    object SealingFailed : CryptoError("sealing failed")

    /** Anything that does not open: wrong key, wrong context or AAD, tampered or truncated bytes. */
    object OpenFailed : CryptoError("open failed")

    object UnsupportedVersion : CryptoError("unsupported envelope version")

    /** An identity box (or a v1 envelope) without a sender tag, or with one that does not verify. */
    object UnauthenticatedSender : CryptoError("unauthenticated sender")

    /** Android/web only: the history key is not in memory, so ratchet state cannot be read or written. */
    object Locked : CryptoError("messaging keys are locked")
}

/** Double Ratchet errors (iOS `DoubleRatchet.RatchetError`, `ios/shroud/Services/Crypto/DoubleRatchet.swift:27-32`). */
sealed class RatchetError(msg: String) : Exception(msg, null, false, false) {
    object InvalidKey : RatchetError("invalid key")
    object DecryptFailed : RatchetError("decrypt failed")
    object SkippedTooFar : RatchetError("skipped too far")
    object NoSession : RatchetError("no session")
}
