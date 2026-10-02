package de.corespace.shroud.ui.contacts

import de.corespace.shroud.core.contacts.ContactInviteParser
import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.model.ChatDeleteOutcome
import de.corespace.shroud.core.net.ContactRequestDto

/**
 * The words of the contacts screens, verbatim from iOS (curly apostrophes and `…` where iOS has
 * them, straight ones where iOS has those), and the small pure rules that pick between them —
 * kept out of the composables so the JVM tests pin them (contacts §5; plan §2.0 rule 8).
 */
object ContactsCopy {
    // ---- Contacts tab (`ContactsView.swift`) ----

    const val TITLE = "Contacts"
    const val PENDING = "Pending"
    const val MY_QR_CODE = "My QR code"
    const val ADD_CONTACT = "Add contact"
    const val SORT_HINT = "Reverses the order"
    const val WANTS_TO_CONNECT = "wants to connect"
    const val REJECT = "Reject"
    const val ACCEPT = "Accept"
    const val YOUR_INVITE = "Your invite"
    const val LOADING_SHARE_CODE = "Loading share code…"
    const val SHOW_QR_CODE = "Show QR code"

    /** "online" / "last seen …" / "offline"; "contact" until the server has said anything (`ContactsView.swift:272-275`). */
    const val CONTACT_FALLBACK_STATUS = "contact"

    /** The sort capsule's label, en dash U+2013 (`ContactsView.swift:69`). */
    fun sortLabel(ascending: Boolean): String = if (ascending) "A–Z" else "Z–A"

    /** What TalkBack reads for the sort capsule (`ContactsView.swift:72`). */
    fun sortSpokenLabel(ascending: Boolean): String = if (ascending) "Sorted A to Z" else "Sorted Z to A"

    /**
     * A pending request's name: the requester's handle, else their id as iOS prints a `UUID`
     * (`uuidString`, upper case), so the fallback and its avatar colour match iOS
     * (`ContactsView.swift:187`).
     */
    fun requestName(request: ContactRequestDto): String =
        request.user?.username ?: request.fromUserId.toString().uppercase()

    // ---- Add Contact (`AddContactSheet.swift`) ----

    const val ADD_CONTACT_TITLE = "Add Contact"
    const val CANCEL = "Cancel"
    const val SCAN_QR_CODE = "Scan QR code"
    const val SCAN_FOOTER = "Scan your contact’s Shroud QR code."
    const val FIELD_PLACEHOLDER = "Code, link, or username"
    const val FIELD_FOOTER = "Paste a share link, enter their short share code, username, or user ID."

    /** The sheet's trailing button (`AddContactSheet.swift:56`). */
    fun addButtonTitle(isAdding: Boolean): String = if (isAdding) "Adding…" else "Add"

    /**
     * The confirmation the Contacts tab shows once a request went out (`AddContactSheet.swift:93-101`);
     * null for a failure, which stays in the sheet.
     */
    fun confirmation(outcome: AddContactOutcome): String? = when (outcome) {
        is AddContactOutcome.Requested -> "Request sent to ${outcome.username}"
        is AddContactOutcome.Added -> "${outcome.username} added"
        is AddContactOutcome.Failed -> null
    }

    /** "Add" is live once the field holds more than whitespace and nothing is in flight (`AddContactSheet.swift:59`). */
    fun canSubmit(text: String, isAdding: Boolean): Boolean = !isAdding && ContactInviteParser.trimSwiftWhitespace(text).isNotEmpty()

    // ---- QR scanner (`QRCodeScannerView.swift`, design oGuCt) ----

    const val SCANNER_HINT = "Point at their Shroud QR code"
    const val SCANNER_NO_CAMERA = "No camera available. Paste their link instead."
    const val CAMERA_OFF_TITLE = "Camera access is off"
    const val CAMERA_OFF_BODY = "Shroud needs the camera to scan a contact’s QR code. Nothing is recorded or sent."
    const val OPEN_SETTINGS = "Open Settings"
    const val ENTER_CODE_INSTEAD = "Enter code instead"

    // ---- My QR Code (`MyQRCodeSheet.swift`) ----

    const val MY_QR_TITLE = "My QR Code"
    const val DONE = "Done"
    const val SHARE_LINK = "Share link"
    const val SHARE_CODE = "Share code"
    const val COPY_LINK = "Copy link"
    const val COPY_SHARE_CODE = "Copy share code"
    const val LINK_COPIED = "Link copied"
    const val CODE_COPIED = "Code copied"
    const val LOADING_YOUR_CODE = "Loading your code…"
    const val MY_QR_HINT = "Friends can scan this QR, open the link, or type your share code to add you."
    const val SHARE_INVITE = "Share invite"

    /** The clipboard entry's label (not shown on most phones; contacts §5.6). */
    const val CLIP_LABEL = "Shroud invite"

    // ---- QR code (`QRCodeImage.swift:19-46`) ----

    const val QR_CODE = "QR code"
    const val QR_FAILED = "Could not create QR"

    // ---- Contact Profile (`ContactProfileView.swift`) ----

    const val BACK = "Back"
    const val EDIT = "Edit"
    const val EDIT_SOON = "Edit coming soon"
    const val SEARCH_SOON = "Search coming soon"
    const val CALL = "Call"
    const val VIDEO = "Video"
    const val SEARCH = "Search"
    const val PROFILE_FALLBACK_STATUS = "Shroud contact"
    const val USERNAME_LABEL = "username"
    const val ENCRYPTION_LABEL = "encryption"
    const val END_TO_END = "End-to-end encrypted"
    const val SAFETY_NUMBER_LABEL = "safety number"
    const val VERIFIED = "Verified"
    const val MARK_AS_VERIFIED = "Mark as Verified"
    const val NOTIFICATIONS = "Notifications"
    const val ENCRYPTION = "Encryption"
    const val ON = "On"
    const val KEY_CHANGED_TITLE = "Encryption key changed"
    const val KEY_CHANGED_BODY =
        "This contact's identity key no longer matches the one saved on this device. Compare safety numbers in person before trusting new messages."
    const val I_VERIFIED = "I verified this contact"
    const val TRUST_TITLE = "Trust the new key?"
    const val TRUST_MESSAGE = "Only do this if you confirmed this contact's safety number through another channel."
    const val TRUST_ACTION = "Trust new key"
    const val NEW_KEY_SAVED = "New encryption key saved"
    const val DELETE_CHAT = "Delete Chat"
    const val DELETE_FOR_ME = "Delete for me"
    const val MUTE_NEEDS_CHAT = "A chat can be muted once it has messages."
    const val NOTIFICATIONS_ON = "Notifications on"
    const val MUTED = "Muted"
    const val BLOCK_MESSAGE = "They can't message you or send contact requests. You'll also stop being contacts."
    const val UNBLOCK_MESSAGE = "They can send you a contact request again. Your existing messages are unaffected."
    const val BLOCK = "Block"
    const val UNBLOCK = "Unblock"
    const val MUTE_MENU = "Mute notifications"

    /** The action tile and its TalkBack label (`ContactProfileView.swift:139, 150`). */
    fun muteTileTitle(isMuted: Boolean): String = if (isMuted) "Unmute" else "Mute"

    /** The block card's text (`ContactProfileView.swift:417`). */
    fun blockRowTitle(isBlocked: Boolean, name: String): String = if (isBlocked) "Unblock $name" else "Block $name"

    /** The block confirmation's title (`ContactProfileView.swift:432`). */
    fun blockConfirmTitle(isBlocked: Boolean, name: String): String = if (isBlocked) "Unblock $name?" else "Block $name?"

    /** The block confirmation's message (`ContactProfileView.swift:443-447`). */
    fun blockConfirmMessage(isBlocked: Boolean): String = if (isBlocked) UNBLOCK_MESSAGE else BLOCK_MESSAGE

    /** The toast after a block or unblock went through (`ContactProfileView.swift:533`). */
    fun blockDone(blocked: Boolean, name: String): String = if (blocked) "$name blocked" else "$name unblocked"

    /** `ContactProfileView.swift:482`. */
    fun deleteConfirmTitle(name: String): String = "Delete chat with $name?"

    /** `ContactProfileView.swift:486`. */
    fun deleteForBoth(name: String): String = "Delete for me and $name"

    /** `ContactProfileView.swift:494-498`; the straight apostrophe is iOS's. */
    fun deleteConfirmMessage(name: String): String =
        "Deleting for both unsends your messages in $name's chat. Their own messages stay unless they allow chats to be cleared for them. They stay in your contacts."

    /**
     * What the profile's mute change says once it went through (`ContactProfileView.swift:303`):
     * "Notifications on" after an unmute, else the chat list's wording ("Muted until 14:30"), or
     * "Muted" when there is no time to name.
     */
    fun muteDone(unmuted: Boolean, label: String?): String = if (unmuted) NOTIFICATIONS_ON else label ?: MUTED

    /** The deletion failed: its message, shown as a failure toast; null when the chat is gone (`ContactProfileView.swift:508-512`). */
    fun deleteFailure(outcome: ChatDeleteOutcome): String? = (outcome as? ChatDeleteOutcome.Failed)?.message
}
