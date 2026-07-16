import SwiftUI

/// Pick a contact to start or open a conversation.
struct NewChatSheet: View {
    @Environment(MessagingController.self) private var messaging
    @Environment(\.dismiss) private var dismiss

    let onSelect: (UUID, String) -> Void

    @State private var searchText = ""

    private var filtered: [ContactItemDTO] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return messaging.contacts }
        return messaging.contacts.filter {
            $0.username.localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        NavigationStack {
            Group {
                if messaging.contacts.isEmpty {
                    ContentUnavailableView(
                        "No contacts",
                        systemImage: "person.2",
                        description: Text("Add a contact first, then start a chat.")
                    )
                } else {
                    List(filtered) { contact in
                        Button {
                            onSelect(contact.userId, contact.username)
                            dismiss()
                        } label: {
                            HStack(spacing: 12) {
                                AvatarView(
                                    initials: AvatarView.initials(for: contact.username),
                                    size: 40,
                                    gradient: AvatarView.gradient(for: contact.username)
                                )
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(contact.username)
                                        .font(.system(size: 16, weight: .semibold))
                                        .foregroundStyle(Theme.textPrimary)
                                    Text(statusLine(for: contact.userId))
                                        .font(.system(size: 13))
                                        .foregroundStyle(
                                            messaging.presenceByUser[contact.userId]?.online == true
                                                ? Theme.accent
                                                : Theme.textSecondary
                                        )
                                }
                                Spacer(minLength: 0)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    .listStyle(.plain)
                }
            }
            .searchable(text: $searchText, prompt: "Search contacts")
            .navigationTitle("New Chat")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .task {
                if messaging.contacts.isEmpty {
                    await messaging.refreshContacts()
                }
            }
        }
    }

    private func statusLine(for userID: UUID) -> String {
        if messaging.presenceByUser[userID]?.online == true {
            return "online"
        }
        if let last = messaging.presenceByUser[userID]?.lastSeenAt {
            return "last seen \(messaging.timeLabel(for: last))"
        }
        return "contact"
    }
}
