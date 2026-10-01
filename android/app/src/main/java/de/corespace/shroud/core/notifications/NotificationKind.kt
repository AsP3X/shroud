package de.corespace.shroud.core.notifications

/**
 * What a notification is about (notifications-push §6.2; one enum everywhere, plan C18). [wire] is
 * the push payload's `kind`; [bodyLine] the generic second line when no content is shown. Published
 * with its full body by W1-INT; W2-NOTIF builds on it.
 */
enum class NotificationKind(val wire: String, val bodyLine: String) {
    Message("message", "New message"),
    Reaction("reaction", "Reacted to your message"),
    ContactRequest("contact_request", "Wants to add you as a contact"),
    Call("call", "Incoming call"),
    VideoCall("video_call", "Incoming video call"),
    MissedCall("missed_call", "Missed call"),
    CallEnded("call_ended", "Call ended"),
    Test("test", "Notifications are working"),
    ;

    /** Handled by the call path (ring, stop the ring, missed call), not the message notifier. */
    val isCall: Boolean get() = this == Call || this == VideoCall || this == CallEnded || this == MissedCall

    companion object {
        /** Unknown kinds read as null: the caller decides (a newer server may send more). */
        fun fromWire(s: String): NotificationKind? = entries.firstOrNull { it.wire == s }
    }
}
