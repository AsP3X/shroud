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
                // Same order as the Contacts tab: never claim "No contacts" before the first
                // load has answered, or when it failed.
                if !messaging.hasLoadedContacts && messaging.contacts.isEmpty {
                    SkeletonChatList(count: 6)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                } else if let error = messaging.contactsError, messaging.contacts.isEmpty {
                    ListLoadErrorView(
                        title: "Can't load contacts",
                        message: error,
                        retry: { await messaging.refreshContacts(force: true) }
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                } else if messaging.contacts.isEmpty {
                    ContentUnavailableView(
                        "No contacts",
                        systemImage: "person.2",
                        description: Text("Add a contact first, then start a chat.")
                    )
                } else if filtered.isEmpty {
                    ContentUnavailableView.search(
                        text: searchText.trimmingCharacters(in: .whitespacesAndNewlines)
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
                                        .lineLimit(1)
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
                            // Inset inside the label so the highlight spans the whole row.
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .contentShape(Rectangle())
                        }
                        // Full-width rows highlight like the Chats and Contacts lists; a
                        // scaling row reads as a glitch. The default backgroundGrouped fill is
                        // #1C1C1E in dark mode, the same as this sheet's elevated row background,
                        // so the press would be invisible there; systemGray5 shows in both modes.
                        .buttonStyle(HighlightRowButtonStyle(fill: Color(uiColor: .systemGray5)))
                        .listRowInsets(EdgeInsets())
                    }
                    .listStyle(.plain)
                }
            }
            // Search narrows the list — animate rows out instead of hard-cutting.
            .animation(Motion.standard, value: filtered.map(\.id))
            .animation(Motion.fade, value: messaging.hasLoadedContacts)
            .animation(Motion.fade, value: messaging.contactsError)
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

    /// Same wording as the Contacts list: "online" / "last seen …" / "offline", and
    /// "contact" until the server has told us anything.
    private func statusLine(for userID: UUID) -> String {
        ChatListFormatting.presenceLabel(for: messaging.presenceByUser[userID]) ?? "contact"
    }
}
