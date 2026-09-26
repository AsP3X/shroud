import SwiftUI

/// Telegram's in-app notification: a card that drops in from the top when something arrives in
/// another chat. Tap opens the chat; a flick up (or four seconds) sends it away.
///
/// Human: It lives only on screen — nothing of it reaches the system's notification centre, so
/// it may show the message text (Settings → Notifications and Sounds → Message Preview).
/// It is a Liquid Glass card, like the system's own banners over an app.
struct InAppNotificationBanner: View {
    let notification: InAppNotification
    let onOpen: () -> Void
    let onDismiss: () -> Void

    @State private var dragOffset: CGFloat = 0

    var body: some View {
        HStack(spacing: 12) {
            avatar
            VStack(alignment: .leading, spacing: 2) {
                Text(notification.title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(1)
                Text(notification.body)
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        // Interactive glass: the card answers the finger before the tap opens the chat.
        .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 24))
        .frame(maxWidth: 500)
        .offset(y: min(0, dragOffset))
        .contentShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .onTapGesture {
            Haptics.impact(.light)
            onOpen()
        }
        .gesture(
            DragGesture(minimumDistance: 6)
                .onChanged { value in dragOffset = value.translation.height }
                .onEnded { value in
                    if value.translation.height < -24 || value.predictedEndTranslation.height < -60 {
                        onDismiss()
                    } else {
                        withAnimation(Motion.snappy) { dragOffset = 0 }
                    }
                }
        )
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Opens the chat")
        .accessibilityAction(named: "Dismiss", onDismiss)
    }

    @ViewBuilder
    private var avatar: some View {
        if let username = notification.username, notification.title == username {
            AvatarView(
                initials: AvatarView.initials(for: username),
                size: 40,
                gradient: AvatarView.gradient(for: username),
                fontSize: 15
            )
        } else {
            BrandLogoMark(size: 40)
        }
    }
}

/// Hosts the current banner above the app, under the in-call overlay.
struct InAppNotificationHost: View {
    @Environment(NotificationsController.self) private var notifications
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack {
            if let banner = notifications.banner {
                InAppNotificationBanner(
                    notification: banner,
                    onOpen: { notifications.openBanner(banner) },
                    onDismiss: { notifications.dismissBanner(id: banner.id) }
                )
                .id(banner.id)
                .padding(.horizontal, 8)
                .padding(.top, 4)
                .transition(
                    reduceMotion
                        ? .opacity
                        : .move(edge: .top).combined(with: .opacity)
                )
            }
            Spacer(minLength: 0)
        }
        .animation(Motion.respecting(reduceMotion, Motion.standard), value: notifications.banner?.id)
    }
}

#Preview {
    VStack(spacing: 16) {
        InAppNotificationBanner(
            notification: InAppNotification(
                kind: .message,
                peerUserID: UUID(),
                username: "alice",
                title: "alice",
                body: "Are we still on for 8?"
            ),
            onOpen: {},
            onDismiss: {}
        )
        InAppNotificationBanner(
            notification: InAppNotification(
                kind: .message,
                peerUserID: UUID(),
                username: "alice",
                title: "Shroud",
                body: "New message"
            ),
            onOpen: {},
            onDismiss: {}
        )
    }
    .padding()
    .background(Theme.backgroundGrouped)
}
