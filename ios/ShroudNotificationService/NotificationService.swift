import UserNotifications

/// Rewrites Shroud's pushes before iOS shows them: the sender's name, opened from its seal, and
/// the app's own wording (`NotificationPayload`).
///
/// Human: This runs outside the app, with the phone locked, for every alert the server sends
/// (they carry `mutable-content`). It reads one thing: the payload key in the shared Keychain,
/// readable after the first unlock. It never touches messages or the encrypted history — a push
/// holds no message to read. When anything is missing it lets the push through as sent
/// ("New message" without a name).
final class NotificationService: UNNotificationServiceExtension {
    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var bestAttempt: UNMutableNotificationContent?

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        guard let content = request.content.mutableCopy() as? UNMutableNotificationContent else {
            contentHandler(request.content)
            return
        }
        self.contentHandler = contentHandler
        bestAttempt = content
        NotificationPayload.dress(content, key: NotificationPayload.storedKey())
        contentHandler(content)
    }

    override func serviceExtensionTimeWillExpire() {
        if let contentHandler, let bestAttempt {
            contentHandler(bestAttempt)
        }
    }
}
