import SwiftUI

/// Design-preview models for main tabs until live chat/contact APIs are wired into the UI.
/// Source: sample rows on `Chats` / `Contacts` frames in `design/iOS-App.pen`.

struct ChatListItem: Identifiable, Hashable {
    let id: String
    let title: String
    let preview: String
    let time: String
    let unreadCount: Int?
    let avatarTop: Color
    let avatarBottom: Color

    var gradient: LinearGradient {
        LinearGradient(colors: [avatarTop, avatarBottom], startPoint: .top, endPoint: .bottom)
    }
}

struct ContactListItem: Identifiable, Hashable {
    let id: String
    let name: String
    let status: String
    let isOnline: Bool
    let avatarTop: Color
    let avatarBottom: Color

    var gradient: LinearGradient {
        LinearGradient(colors: [avatarTop, avatarBottom], startPoint: .top, endPoint: .bottom)
    }

    var sectionKey: String {
        String(name.prefix(1)).uppercased()
    }
}

struct CallListItem: Identifiable, Hashable {
    enum Direction: Hashable {
        case incoming
        case outgoing
        case missed
    }

    let id: String
    let name: String
    let detail: String
    let direction: Direction
    let avatarTop: Color
    let avatarBottom: Color

    var gradient: LinearGradient {
        LinearGradient(colors: [avatarTop, avatarBottom], startPoint: .top, endPoint: .bottom)
    }
}

enum MainSampleData {
    static let chats: [ChatListItem] = [
        .init(
            id: "design-team",
            title: "Design Team",
            preview: "Nina: Final icons are ready",
            time: "12:45",
            unreadCount: 3,
            avatarTop: Color(red: 1, green: 159 / 255, blue: 90 / 255),
            avatarBottom: Color(red: 247 / 255, green: 107 / 255, blue: 28 / 255)
        ),
        .init(
            id: "jane",
            title: "Jane Cooper",
            preview: "Sounds good, see you tomorrow!",
            time: "12:10",
            unreadCount: 1,
            avatarTop: Color(red: 124 / 255, green: 122 / 255, blue: 255 / 255),
            avatarBottom: Color(red: 94 / 255, green: 92 / 255, blue: 230 / 255)
        ),
        .init(
            id: "mom",
            title: "Mom",
            preview: "Call me when you get home",
            time: "11:32",
            unreadCount: nil,
            avatarTop: Color(red: 1, green: 122 / 255, blue: 158 / 255),
            avatarBottom: Color(red: 230 / 255, green: 74 / 255, blue: 114 / 255)
        ),
        .init(
            id: "devon",
            title: "Devon Lane",
            preview: "You: The keys are under the mat",
            time: "09:58",
            unreadCount: nil,
            avatarTop: Color(red: 74 / 255, green: 199 / 255, blue: 250 / 255),
            avatarBottom: Color(red: 46 / 255, green: 143 / 255, blue: 224 / 255)
        ),
        .init(
            id: "hiking",
            title: "Hiking Crew",
            preview: "Sam: Trail is open again this weekend",
            time: "Yesterday",
            unreadCount: 12,
            avatarTop: Color(red: 90 / 255, green: 217 / 255, blue: 124 / 255),
            avatarBottom: Color(red: 47 / 255, green: 168 / 255, blue: 91 / 255)
        ),
        .init(
            id: "arlene",
            title: "Arlene McCoy",
            preview: "Photo",
            time: "Yesterday",
            unreadCount: nil,
            avatarTop: Color(red: 199 / 255, green: 124 / 255, blue: 255 / 255),
            avatarBottom: Color(red: 155 / 255, green: 74 / 255, blue: 230 / 255)
        ),
        .init(
            id: "bank",
            title: "Bank Alerts",
            preview: "Your statement for June is ready",
            time: "Mon",
            unreadCount: nil,
            avatarTop: Color(red: 142 / 255, green: 142 / 255, blue: 147 / 255),
            avatarBottom: Color(red: 95 / 255, green: 95 / 255, blue: 102 / 255)
        ),
        .init(
            id: "guy",
            title: "Guy Hawkins",
            preview: "You: Let's sync on the proposal Friday",
            time: "Mon",
            unreadCount: nil,
            avatarTop: Color(red: 1, green: 198 / 255, blue: 90 / 255),
            avatarBottom: Color(red: 230 / 255, green: 154 / 255, blue: 28 / 255)
        ),
    ]

    static let contacts: [ContactListItem] = [
        .init(
            id: "arlene",
            name: "Arlene McCoy",
            status: "last seen 25 minutes ago",
            isOnline: false,
            avatarTop: Color(red: 199 / 255, green: 124 / 255, blue: 255 / 255),
            avatarBottom: Color(red: 155 / 255, green: 74 / 255, blue: 230 / 255)
        ),
        .init(
            id: "albert",
            name: "Albert Flores",
            status: "last seen recently",
            isOnline: false,
            avatarTop: Color(red: 74 / 255, green: 199 / 255, blue: 250 / 255),
            avatarBottom: Color(red: 46 / 255, green: 143 / 255, blue: 224 / 255)
        ),
        .init(
            id: "devon",
            name: "Devon Lane",
            status: "online",
            isOnline: true,
            avatarTop: Color(red: 74 / 255, green: 199 / 255, blue: 250 / 255),
            avatarBottom: Color(red: 46 / 255, green: 143 / 255, blue: 224 / 255)
        ),
        .init(
            id: "dianne",
            name: "Dianne Russell",
            status: "last seen yesterday",
            isOnline: false,
            avatarTop: Color(red: 1, green: 159 / 255, blue: 90 / 255),
            avatarBottom: Color(red: 247 / 255, green: 107 / 255, blue: 28 / 255)
        ),
        .init(
            id: "guy",
            name: "Guy Hawkins",
            status: "last seen 2 hours ago",
            isOnline: false,
            avatarTop: Color(red: 1, green: 198 / 255, blue: 90 / 255),
            avatarBottom: Color(red: 230 / 255, green: 154 / 255, blue: 28 / 255)
        ),
        .init(
            id: "jane",
            name: "Jane Cooper",
            status: "online",
            isOnline: true,
            avatarTop: Color(red: 124 / 255, green: 122 / 255, blue: 255 / 255),
            avatarBottom: Color(red: 94 / 255, green: 92 / 255, blue: 230 / 255)
        ),
        .init(
            id: "jenny",
            name: "Jenny Wilson",
            status: "last seen just now",
            isOnline: false,
            avatarTop: Color(red: 90 / 255, green: 217 / 255, blue: 124 / 255),
            avatarBottom: Color(red: 47 / 255, green: 168 / 255, blue: 91 / 255)
        ),
    ]

    static let calls: [CallListItem] = [
        .init(
            id: "c1",
            name: "Jane Cooper",
            detail: "Outgoing · 12:08",
            direction: .outgoing,
            avatarTop: Color(red: 124 / 255, green: 122 / 255, blue: 255 / 255),
            avatarBottom: Color(red: 94 / 255, green: 92 / 255, blue: 230 / 255)
        ),
        .init(
            id: "c2",
            name: "Mom",
            detail: "Missed · 11:30",
            direction: .missed,
            avatarTop: Color(red: 1, green: 122 / 255, blue: 158 / 255),
            avatarBottom: Color(red: 230 / 255, green: 74 / 255, blue: 114 / 255)
        ),
        .init(
            id: "c3",
            name: "Devon Lane",
            detail: "Incoming · Yesterday",
            direction: .incoming,
            avatarTop: Color(red: 74 / 255, green: 199 / 255, blue: 250 / 255),
            avatarBottom: Color(red: 46 / 255, green: 143 / 255, blue: 224 / 255)
        ),
    ]
}
