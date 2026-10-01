package de.corespace.shroud.core.model

/**
 * What the peer is doing right now (`ShroudUI/Components/TypingIndicatorBubble.swift:4-30`).
 * Here rather than with the domain models because the W1 UI kit's `ChatRow` and `TypingLabel`
 * need it (plan §1.7.1).
 *
 * @property label the word beside the live glyph in headers and list rows (`:9-14`).
 * @property spokenLabel what TalkBack says instead: the glyph that makes "recording" mean a voice
 *   message is silent (`:17-22`).
 * @property bubbleLabel the typing bubble's accessibility label (`accessibilityBubble`, `:24-29`).
 */
enum class ChatPeerActivity(val label: String, val spokenLabel: String, val bubbleLabel: String) {
    Typing("typing", "typing", "Typing"),
    Recording("recording", "recording a voice message", "Recording a voice message"),
}
