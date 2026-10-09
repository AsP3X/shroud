import Foundation
import Observation
import UIKit
import UserNotifications

/// Something that arrived while the app is open, shown as a banner at the top of the screen.
struct InAppNotification: Identifiable, Equatable {
    let id = UUID()
    let kind: NotificationPayload.Kind
    let peerUserID: UUID?
    let username: String?
    let title: String
    let body: String
}

/// A notification (or banner) tap, waiting for the chats to open it.
struct NotificationOpenRequest: Equatable {
    let kind: NotificationPayload.Kind
    let peerUserID: UUID?
    let username: String?
}

/// Notifications while the app runs: permission, in-app banners and sounds, taps, the icon
/// badge and delivered notifications.
///
/// Human: Pushes (from the server, through APNs) cover the app closed or locked; this covers it
/// open. The open app reads messages, so its banner can show the text; the system notification
/// centre never gets it — anything the app posts there is worded like a push (a name at most).
/// Agent: READS NotificationPreferences; WRITES UNUserNotificationCenter (badge, delivered,
/// local requests); `pendingOpen` is consumed by MainTabView; `banner` by the root overlay.
@MainActor
@Observable
final class NotificationsController {
    static let shared = NotificationsController()

    let preferences = NotificationPreferences.shared
    private(set) var authorization: UNAuthorizationStatus = .notDetermined
    private(set) var banner: InAppNotification?
    /// Set by a tap; the chats open it once they are on screen (after an unlock, if need be).
    var pendingOpen: NotificationOpenRequest?

    /// The chats are unlocked and on screen: arrivals get banners instead of system alerts.
    var isUnlocked = false
    /// An account is signed in. Signed out, a push that still arrives (the server missed the
    /// logout) is not shown in the app.
    var isSignedIn = false
    /// The chat on screen (its messages need no banner).
    var activePeerID: UUID?
    /// The server was told this iPhone is not in front, so it pushes. A local notification
    /// on top of that push would show the same message twice.
    private var pushCoversBackground = false

    private var bannerDismissTask: Task<Void, Never>?
    private var lastAlertAt: Date?
    /// Banners that come this soon after another replace it silently.
    private let quietInterval: TimeInterval = 1.2
    private let bannerLifetime: Duration = .seconds(4)
    /// VoiceOver hears the banner announced, then needs time to reach its open and Dismiss actions.
    private let voiceOverBannerLifetime: Duration = .seconds(10)

    private init() {}

    // MARK: - Permission

    func refreshAuthorization() async {
        authorization = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
    }

    /// Asks the first time (the first unlock with notifications on); later calls only read.
    @discardableResult
    func requestAuthorizationIfNeeded() async -> Bool {
        let center = UNUserNotificationCenter.current()
        if await center.notificationSettings().authorizationStatus == .notDetermined {
            _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
        }
        await refreshAuthorization()
        return authorization == .authorized || authorization == .provisional
    }

    // MARK: - Arrivals while the app runs

    /// A message, reaction or contact request arrived over the socket.
    ///
    /// Human: The server does not push to a device with a live socket, so this is the only
    /// notice the user gets. Open app: a banner (unless it is about the chat on screen), a
    /// sound, a tap. App in the background (a call keeps it running): a system notification,
    /// worded like a push. A muted chat stays quiet; a contact request ignores chat mutes.
    func announce(
        kind: NotificationPayload.Kind,
        peerUserID: UUID?,
        username: String?,
        conversationID: UUID?,
        text: String?,
        muted: Bool
    ) {
        if muted, kind != .contactRequest { return }
        if kind == .reaction, !preferences.reactions { return }
        if kind == .contactRequest, !preferences.contactRequests { return }

        let name = preferences.showSender ? username : nil
        if UIApplication.shared.applicationState != .active {
            guard preferences.enabled, !pushCoversBackground else { return }
            postLocal(kind: kind, name: name, peerUserID: peerUserID, conversationID: conversationID)
            return
        }
        if let peerUserID, peerUserID == activePeerID, kind == .message || kind == .reaction { return }

        let quiet = lastAlertAt.map { Date().timeIntervalSince($0) < quietInterval } ?? false
        lastAlertAt = Date()
        if !quiet {
            if preferences.inAppSounds { preferences.sound.play() }
            if preferences.inAppVibrate { Haptics.impact(.medium) }
        }
        guard preferences.inAppBanners else { return }
        let preview = preferences.showPreview ? text?.trimmingCharacters(in: .whitespacesAndNewlines) : nil
        let body: String
        switch kind {
        case .message:
            body = (preview?.isEmpty == false ? preview : nil) ?? NotificationPayload.body(for: kind)
        default:
            body = NotificationPayload.body(for: kind)
        }
        show(InAppNotification(
            kind: kind,
            peerUserID: peerUserID,
            username: username,
            title: name ?? "Shroud",
            body: body
        ))
    }

    func show(_ notification: InAppNotification) {
        banner = notification
        bannerDismissTask?.cancel()
        let lifetime = UIAccessibility.isVoiceOverRunning ? voiceOverBannerLifetime : bannerLifetime
        bannerDismissTask = Task { [weak self] in
            try? await Task.sleep(for: lifetime)
            guard !Task.isCancelled else { return }
            self?.dismissBanner(id: notification.id)
        }
    }

    func setPushCoversBackground(_ covers: Bool) {
        pushCoversBackground = covers
    }

    func dismissBanner(id: UUID? = nil) {
        guard id == nil || banner?.id == id else { return }
        bannerDismissTask?.cancel()
        banner = nil
    }

    func openBanner(_ notification: InAppNotification) {
        dismissBanner(id: notification.id)
        pendingOpen = NotificationOpenRequest(
            kind: notification.kind,
            peerUserID: notification.peerUserID,
            username: notification.username
        )
    }

    /// A system notification from the app itself (it was in the background with its socket
    /// still open). Worded like a push: a name at most, never the message.
    private func postLocal(kind: NotificationPayload.Kind, name: String?, peerUserID: UUID?, conversationID: UUID?) {
        let content = UNMutableNotificationContent()
        if let name { content.title = name }
        content.body = NotificationPayload.body(for: kind)
        content.threadIdentifier = conversationID?.uuidString.lowercased() ?? (kind == .contactRequest ? "contacts" : "shroud")
        content.sound = preferences.sound.notificationSound
        var app: [String: Any] = ["v": 1, "k": kind.rawValue]
        if let peerUserID { app["p"] = peerUserID.uuidString.lowercased() }
        if let conversationID { app["c"] = conversationID.uuidString.lowercased() }
        content.userInfo = ["shroud": app]
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { _ in }
    }

    // MARK: - Pushes that reach the open app, and taps

    /// A push arrived with the app in front: while the chats are open the app shows its own
    /// banner (so there is one look for both), otherwise the system shows it.
    func presentation(for notification: UNNotification) -> UNNotificationPresentationOptions {
        let content = notification.request.content
        guard isSignedIn else { return [] }
        guard let contents = NotificationPayload.parse(content.userInfo) else { return [.banner, .list, .sound, .badge] }
        if contents.kind == .test { return [.banner, .list, .sound] }
        guard isUnlocked else { return [.banner, .list, .sound, .badge] }
        if contents.kind == .message || contents.kind == .reaction,
           let peer = contents.peerUserID,
           peer == activePeerID
        {
            return []
        }
        if preferences.inAppBanners {
            show(InAppNotification(
                kind: contents.kind,
                peerUserID: contents.peerUserID,
                username: content.title.isEmpty ? nil : content.title,
                title: content.title.isEmpty ? "Shroud" : content.title,
                body: content.body
            ))
        }
        if preferences.inAppSounds { preferences.sound.play() }
        return []
    }

    /// The user tapped a notification: open what it is about.
    func handleTap(_ contents: NotificationPayload.Contents?, title: String?) {
        // Not gated on `isSignedIn`: a tap that launches the app arrives before the session is
        // read. A signed-out launch drops it (`RootView`), and logout clears it.
        guard let contents, contents.kind != .test else { return }
        pendingOpen = NotificationOpenRequest(
            kind: contents.kind,
            peerUserID: contents.peerUserID,
            username: title?.isEmpty == false ? title : nil
        )
    }

    // MARK: - Badge and delivered notifications

    /// The app icon's count (0 when the badge is off).
    func setBadge(_ count: Int) {
        let value = preferences.badge ? max(0, count) : 0
        UNUserNotificationCenter.current().setBadgeCount(value) { _ in }
    }

    /// Closes a chat's notifications once it has been read (here or on another device). The
    /// extension files them under the chat's id; one it could not open keeps the server's
    /// keyed thread (`NotificationPayload.threadID`).
    func clearDelivered(conversationID: UUID) {
        var threads: Set<String> = [conversationID.uuidString.lowercased()]
        if let key = NotificationPayload.storedKey() {
            threads.insert(NotificationPayload.threadID(for: conversationID, key: key))
        }
        clearDelivered(threads: threads)
    }

    private func clearDelivered(threads: Set<String>) {
        Task {
            let center = UNUserNotificationCenter.current()
            let ids = await center.deliveredNotifications()
                .filter { threads.contains($0.request.content.threadIdentifier.lowercased()) }
                .map(\.request.identifier)
            guard !ids.isEmpty else { return }
            center.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    /// Logout: nothing of the account stays in memory either. The wipe took these settings off
    /// the disk; a tap still waiting to open a chat and the banner go with them.
    func forgetAccount() {
        pendingOpen = nil
        activePeerID = nil
        lastAlertAt = nil
        dismissBanner()
        preferences.reset()
    }

    // MARK: - Server

    /// Sends the settings the server pushes by. Errors are the caller's to show.
    func pushSettings(token: String) async throws {
        _ = try await NotificationsService().update(preferences.serverPatch, token: token)
    }

    /// A notification through APNs, even with the app open: proves the whole path.
    func sendTest(token: String) async -> String {
        // Apple takes a push for an iPhone that blocks Shroud and drops it there: "sent" would
        // be a promise nothing keeps.
        await refreshAuthorization()
        switch authorization {
        case .denied: return "Notifications are off for Shroud. Turn them on in iOS Settings, then try again."
        case .notDetermined: return "Shroud has not been allowed to notify yet. Tap Allow notifications above first."
        default: break
        }
        do {
            let outcome = try await NotificationsService().sendTest(token: token)
            switch outcome.status {
            case "sent": return "Sent. It should arrive in a moment."
            case "not_registered":
                UIApplication.shared.registerForRemoteNotifications()
                return "This \(UIDevice.current.model) was not registered for notifications. Shroud registered it now — try again in a moment."
            case "not_configured": return "This server is not set up to send Apple push notifications."
            case "misconfigured":
                // Apple answered, and refused the server's own key or topic: not this iPhone.
                let reason = outcome.detail.map { " (\($0))" } ?? ""
                return "Apple refused this server's push setup\(reason). Whoever runs the server needs to check its APNs key, team and topic."
            case "rejected":
                UIApplication.shared.registerForRemoteNotifications()
                return "Apple refused this \(UIDevice.current.model)'s notification token. Shroud registered again — try again in a moment."
            default: return "Apple's notification service could not be reached. Try again in a moment."
            }
        } catch {
            return SessionController.userMessage(for: error)
        }
    }
}

extension NotificationSound {
    /// The sound a system notification from the app itself plays.
    var notificationSound: UNNotificationSound? {
        switch self {
        case .none: nil
        case .standard: .default
        default: UNNotificationSound(named: UNNotificationSoundName("\(rawValue).wav"))
        }
    }
}
