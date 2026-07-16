import SwiftUI

/// Contacts directory — live API (requests + list).
struct ContactsView: View {
    @Environment(MessagingController.self) private var messaging
    @Environment(SessionController.self) private var session

    @State private var searchText = ""
    @State private var showAdd = false
    @State private var addUserID = ""
    @State private var addError: String?
    @State private var isAdding = false
    @State private var sortAscending = true
    /// Dedicated path type for this tab’s stack (avoids NavigationLink + outer path conflicts).
    @State private var path: [ChatRoute] = []

    private var filtered: [ContactItemDTO] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return messaging.contacts }
        return messaging.contacts.filter {
            $0.username.localizedCaseInsensitiveContains(query)
                || $0.userId.uuidString.localizedCaseInsensitiveContains(query)
        }
    }

    private var sections: [(letter: String, items: [ContactItemDTO])] {
        let grouped = Dictionary(grouping: filtered) { contact in
            String(contact.username.prefix(1)).uppercased()
        }
        let keys = sortAscending ? grouped.keys.sorted() : grouped.keys.sorted().reversed()
        return keys.map { key in
            let items = (grouped[key] ?? []).sorted {
                let cmp = $0.username.localizedCaseInsensitiveCompare($1.username)
                return sortAscending ? cmp == .orderedAscending : cmp == .orderedDescending
            }
            return (key, items)
        }
    }

    var body: some View {
        NavigationStack(path: $path) {
            MainScrollScreen(title: "Contacts", collapsesTitle: true) {
                Button(sortAscending ? "A–Z" : "Z–A") {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        sortAscending.toggle()
                    }
                }
                .font(.system(size: 16))
                .foregroundStyle(Theme.accent)
            } navTrailing: {
                Button {
                    showAdd = true
                } label: {
                    Image(systemName: "person.badge.plus")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Add contact")
            } accessory: {
                SearchField(text: $searchText)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 10)
            } content: {
                LazyVStack(spacing: 0, pinnedViews: [.sectionHeaders]) {
                    if !messaging.incomingRequests.isEmpty {
                        Section {
                            ForEach(messaging.incomingRequests) { request in
                                requestRow(request)
                            }
                        } header: {
                            sectionHeader("Pending")
                        }
                    }

                    ForEach(sections, id: \.letter) { section in
                        Section {
                            ForEach(section.items) { contact in
                                Button {
                                    path.append(
                                        .conversation(
                                            peerID: contact.userId,
                                            username: contact.username
                                        )
                                    )
                                } label: {
                                    ChatRowView(
                                        title: contact.username,
                                        subtitle: contactStatus(contact),
                                        subtitleAccent: messaging.presenceByUser[contact.userId]?.online == true,
                                        avatarGradient: AvatarView.gradient(for: contact.username)
                                    )
                                }
                                .buttonStyle(.plain)
                            }
                        } header: {
                            sectionHeader(section.letter)
                        }
                    }

                    if sections.isEmpty && messaging.incomingRequests.isEmpty {
                        emptyState
                    }

                    if let myID = session.userID {
                        shareIDFooter(myID)
                    }

                    Color.clear.frame(height: 16)
                }
            }
            .background(Theme.background)
            .navigationDestination(for: ChatRoute.self) { route in
                switch route {
                case let .conversation(peerID, username):
                    ConversationView(peerUserID: peerID, peerUsername: username)
                }
            }
            .refreshable {
                await messaging.refreshContacts()
            }
            .sheet(isPresented: $showAdd) {
                addContactSheet
            }
            .task {
                await messaging.refreshContacts()
            }
        }
    }

    private func requestRow(_ request: ContactRequestDTO) -> some View {
        let name = request.user?.username ?? request.fromUserId.uuidString
        return HStack(spacing: 12) {
            AvatarView(initials: AvatarView.initials(for: name), gradient: AvatarView.gradient(for: name))
            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                Text("wants to connect")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
            }
            Spacer()
            Button("Reject") {
                Task { await messaging.rejectRequest(request) }
            }
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(Theme.danger)
            Button("Accept") {
                Task { await messaging.acceptRequest(request) }
            }
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(Theme.accent)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(Theme.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 3)
            .background(.ultraThinMaterial)
    }

    private func contactStatus(_ contact: ContactItemDTO) -> String {
        if messaging.typingPeerIDs.contains(contact.userId) {
            return "typing…"
        }
        if let presence = messaging.presenceByUser[contact.userId] {
            if presence.online { return "online" }
            if let last = presence.lastSeenAt {
                return "last seen \(messaging.timeLabel(for: last))"
            }
            return "offline"
        }
        return "contact"
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Text(messaging.isLoadingContacts ? "Loading…" : "No contacts yet")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            Text("Add someone with their user ID.")
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 48)
    }

    private func shareIDFooter(_ id: UUID) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Your user ID")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Theme.textSecondary)
            Text(id.uuidString.lowercased())
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Theme.textPrimary)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .padding(.top, 24)
    }

    private var addContactSheet: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("User ID (UUID)", text: $addUserID)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.body, design: .monospaced))
                } footer: {
                    Text("Ask your contact to share their user ID from Contacts.")
                }
                if let addError {
                    Section {
                        Text(addError)
                            .foregroundStyle(Theme.danger)
                            .font(.system(size: 14))
                    }
                }
            }
            .navigationTitle("Add Contact")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { showAdd = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(isAdding ? "Adding…" : "Add") {
                        Task {
                            isAdding = true
                            addError = await messaging.addContact(byUserIDString: addUserID)
                            isAdding = false
                            if addError == nil {
                                showAdd = false
                                addUserID = ""
                            }
                        }
                    }
                    .disabled(isAdding || addUserID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }
}

#Preview {
    ContactsView()
        .environment(MessagingController())
        .environment(SessionController())
}
