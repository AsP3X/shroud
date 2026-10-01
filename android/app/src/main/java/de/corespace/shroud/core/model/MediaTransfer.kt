package de.corespace.shroud.core.model

/**
 * Live progress of one media message's bytes, in either direction (`MessagingController.MediaTransfer`,
 * `MessagingController.swift:380-421`; messaging-core §2.2). Compress → upload fills one ring, so the
 * phases live in the model rather than in the view. Seam published by W1-INT (plan §1.7.5).
 */
data class MediaTransfer(
    val phase: Phase,
    val isUpload: Boolean,
    /** 0…1 within the current phase; null until a length is known. */
    val fraction: Double? = null,
    /** Full payload size, for the "1.2 MB / 4.8 MB" readout. */
    val totalBytes: Long? = null,
) {
    enum class Phase {
        /** Compressing / exporting, before anything touches the network (upload only). */
        Preparing,

        /** Bytes on the wire. */
        Transferring,

        /** Decrypting, thumbnailing or sealing: real work, but no byte counter. */
        Finishing,
    }

    /** One 0…1 value for the ring, so compress → upload reads as a single fill. */
    val ringFraction: Double
        get() = when (phase) {
            Phase.Preparing -> (fraction ?: 0.0) * PREPARE_SHARE
            Phase.Transferring -> if (isUpload) PREPARE_SHARE + (fraction ?: 0.0) * (1 - PREPARE_SHARE) else fraction ?: 0.0
            Phase.Finishing -> 1.0
        }

    /** No trustworthy number to draw: the ring spins instead of filling. */
    val isIndeterminate: Boolean get() = phase == Phase.Finishing || fraction == null

    /** Bytes already moved, for the readout under the ring; only while transferring with both known. */
    val movedBytes: Long?
        get() {
            val total = totalBytes ?: return null
            val f = fraction ?: return null
            if (total <= 0 || phase != Phase.Transferring) return null
            return (total * f).toLong()
        }

    companion object {
        /** How much of the ring compression owns before the upload takes over (`MessagingController.swift:420`). */
        const val PREPARE_SHARE = 0.3
    }
}
