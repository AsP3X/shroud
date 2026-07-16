import SwiftUI

/// Contacts directory — live API (requests + list).
/// Opens chats with a standard **horizontal push / swipe** transition.
struct ContactsView: View {
    @Environment(MessagingController.self) private var messaging
    @Environment(SessionController.self) private var session

    @Binding var path: [ChatRoute]
    @State private var searchText = ""
    @State private var showAdd = false
    @State private var showMyQR = false
    @State private var sortAscending = true

    init(path: Binding<[ChatRoute]> = .constant([])) {
        _path = path
    }

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
                HStack(spacing: 14) {
                    Button {
                        showMyQR = true
                    } label: {
                        Image(systemName: "qrcode")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(Theme.accent)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("My QR code")

                    Button {
                        showAdd = true
                    } label: {
                        Image(systemName: "person.badge.plus")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(Theme.accent)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Add contact")
                }
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
                                NavigationLink(
                                    value: ChatRoute.conversation(
                                        peerID: contact.userId,
                                        username: contact.username
                                    )
                                ) {
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

                    shareFooter

                    Color.clear.frame(height: 16)
                }
            }
            .background(Theme.background)
            .navigationDestination(for: ChatRoute.self) { route in
                switch route {
                case let .conversation(peerID, username):
                    ConversationView(peerUserID: peerID, peerUsername: username)
                        .navigationTransition(.automatic)
                }
            }
            .sheet(isPresented: $showAdd) {
                AddContactSheet()
            }
            .sheet(isPresented: $showMyQR) {
                MyQRCodeSheet()
            }
        }
        .task {
            await messaging.refreshContacts()
            if session.shareCode == nil {
                await session.validateSessionIfNeeded()
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
            Text("Scan a QR code or enter a share code to add someone.")
                .font(.system(size: 14))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 48)
    }

    private var shareFooter: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Your invite")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Theme.textSecondary)

            if let code = session.shareCode {
                Text(code)
                    .font(.system(size: 18, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Theme.textPrimary)
                    .textSelection(.enabled)

                Text(ContactInviteParser.shareURL(code: code).absoluteString)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(Theme.textSecondary)
                    .textSelection(.enabled)
                    .lineLimit(2)
            } else {
                Text("Loading share code…")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
            }

            Button {
                showMyQR = true
            } label: {
                Label("Show QR code", systemImage: "qrcode")
                    .font(.system(size: 14, weight: .semibold))
            }
            .buttonStyle(.plain)
            .foregroundStyle(Theme.accent)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .padding(.top, 24)
    }
}

#Preview {
    ContactsView()
        .environment(MessagingController())
        .environment(SessionController())
}
