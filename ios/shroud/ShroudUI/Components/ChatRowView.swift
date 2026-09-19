import SwiftUI

/// List row for chats and contacts — maps to reusable `Chat Row` in `iOS-App.pen`.
struct ChatRowView: View {
    let title: String
    let subtitle: String
    var time: String? = nil
    var unreadCount: Int? = nil
    var subtitleAccent: Bool = false
    /// "typing" / "recording" with the live dots replaces the subtitle.
    var activity: ChatPeerActivity? = nil
    var avatarGradient: LinearGradient? = nil
    /// When set, shows a symbol instead of initials (e.g. Notes bookmark).
    var avatarSystemImage: String? = nil

    private var initials: String { AvatarView.initials(for: title) }
    private var gradient: LinearGradient {
        avatarGradient ?? AvatarView.gradient(for: title)
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            if let avatarSystemImage {
                ZStack {
                    Circle()
                        .fill(gradient)
                        .frame(width: 52, height: 52)
                    Image(systemName: avatarSystemImage)
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(Color.white)
                }
                .accessibilityHidden(true)
            } else {
                AvatarView(initials: initials, gradient: gradient)
            }

            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(title)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    if let time {
                        Text(time)
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.textSecondary)
                    }
                }

                HStack(alignment: .center, spacing: 8) {
                    ZStack(alignment: .leading) {
                        // Activity replaces the preview in place instead of hard-cutting.
                        if let activity {
                            TypingLabel(activity: activity, font: .system(size: 14))
                                .transition(.opacity)
                        } else {
                            Text(subtitle)
                                .font(.system(size: 14))
                                .foregroundStyle(subtitleAccent ? Theme.accent : Theme.textSecondary)
                                .lineLimit(1)
                                .contentTransition(.opacity)
                                .transition(.opacity)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .animation(Motion.fade, value: subtitle)
                    .animation(Motion.fade, value: activity)
                    .animation(Motion.snappy, value: subtitleAccent)

                    if let unreadCount, unreadCount > 0 {
                        Text(unreadBadgeText(unreadCount))
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(Color.white)
                            // Rolls the digits when another message lands.
                            .contentTransition(.numericText(value: Double(unreadCount)))
                            .monospacedDigit()
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(Theme.accent)
                            .clipShape(Capsule())
                            .transition(Motion.iconSwap)
                    }
                }
                // A new unread badge pops; an increment rolls.
                .animation(Motion.bouncy, value: unreadCount)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        // No opaque fill here — the row press highlight lives behind it (`HighlightRowButtonStyle`).
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
    }

    private func unreadBadgeText(_ count: Int) -> String {
        count > 99 ? "99+" : "\(count)"
    }

    private var accessibilityLabel: String {
        var parts = [title, activity?.spokenLabel ?? subtitle]
        if let time { parts.append(time) }
        if let unreadCount, unreadCount > 0 {
            parts.append("\(unreadCount) unread")
        }
        return parts.joined(separator: ", ")
    }
}

#Preview {
    VStack(spacing: 0) {
        ChatRowView(
            title: "Design Team",
            subtitle: "Nina: Final icons are ready",
            time: "12:45",
            unreadCount: 3
        )
        ChatRowView(
            title: "Jane Cooper",
            subtitle: "online",
            subtitleAccent: true,
            activity: .recording
        )
    }
    .background(Theme.background)
}
