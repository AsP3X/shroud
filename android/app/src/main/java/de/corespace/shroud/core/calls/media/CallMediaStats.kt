package de.corespace.shroud.core.calls.media

/**
 * Reading a WebRTC stats report without the WebRTC classes, so the JVM tests can feed a fake
 * report (ME:486-533; web `remoteFingerprint` in `web/src/calls/service.ts`).
 *
 * The certificate fingerprint is returned as the report has it. The call's safety check compares
 * it to the sealed SDP with [de.corespace.shroud.core.calls.signal.CallSdp.matches], which ignores
 * case and colons — the same comparison the web client makes.
 */
data class StatRow(val id: String, val type: String, val members: Map<String, Any?>)

/** Linear 0…1 from the sender's `media-source` (else `track`) `audioLevel`. Null when it is not there. */
fun audioLevel(rows: Collection<StatRow>): Float? {
    val source = rows.firstOrNull { it.type == "media-source" } ?: rows.firstOrNull { it.type == "track" } ?: return null
    return parseLevel(source.members["audioLevel"])
}

/**
 * SHA-256 fingerprint of the certificate the handshake used: the transport's `remoteCertificateId`
 * names the certificate stat. Null until stats have one, or when the algorithm is not SHA-256.
 */
fun remoteFingerprint(rows: Collection<StatRow>): String? {
    val byId = rows.associateBy { it.id }
    val transport = rows.firstOrNull { it.type == "transport" } ?: return null
    val remoteId = transport.members["remoteCertificateId"] as? String ?: return null
    val cert = byId[remoteId] ?: return null
    if (cert.type != "certificate") return null
    val algorithm = (cert.members["fingerprintAlgorithm"] as? String)?.lowercase()
    if (algorithm != null && algorithm != "sha-256") return null
    val fingerprint = cert.members["fingerprint"] as? String ?: return null
    if (fingerprint.isBlank()) return null
    return fingerprint
}

/** A number, or a number written as text. Anything else, or a non-finite value, is unknown. */
fun parseLevel(raw: Any?): Float? {
    val value = when (raw) {
        is Number -> raw.toFloat()
        is String -> raw.toFloatOrNull()
        else -> null
    } ?: return null
    if (!value.isFinite()) return null
    return value.coerceIn(0f, 1f)
}
