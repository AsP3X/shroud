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

    /// Skeleton stands in only for the *first* load — a refresh over existing rows keeps the
    /// list. Keyed on `hasLoadedContacts` (not `isLoadingContacts`) so the contacts poll
    /// can't flip an empty list back to placeholders on every tick.
    private var showsSkeleton: Bool {
        !messaging.hasLoadedContacts && messaging.contacts.isEmpty && searchText.isEmpty
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
                Button {
                    // Sections re-sort with a spring so the letters visibly travel.
                    withAnimation(Motion.standard) {
                        sortAscending.toggle()
                    }
                } label: {
                    Text(sortAscending ? "A–Z" : "Z–A")
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.accent)
                        .contentTransition(.opacity)
                        .frame(height: 44)
                        .contentShape(Rectangle())
                }
                .pressable(scale: 0.92)
            } navTrailing: {
                HStack(spacing: 14) {
                    Button {
                        showMyQR = true
                    } label: {
                        Image(systemName: "qrcode")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(Theme.accent)
                            .frame(width: 40, height: 44)
                            .contentShape(Rectangle())
                    }
                    .pressable(scale: 0.88)
                    .accessibilityLabel("My QR code")

                    Button {
                        showAdd = true
                    } label: {
                        Image(systemName: "person.badge.plus")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(Theme.accent)
                            .frame(width: 40, height: 44, alignment: .trailing)
                            .contentShape(Rectangle())
                    }
                    .pressable(scale: 0.88)
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

                    ForEach(Array(sections.enumerated()), id: \.element.letter) { sectionIndex, section in
                        Section {
                            ForEach(Array(section.items.enumerated()), id: \.element.id) { rowIndex, contact in
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
                                        isTyping: messaging.typingPeerIDs.contains(contact.userId),
                                        avatarGradient: AvatarView.gradient(for: contact.username)
                                    )
                                }
                                .buttonStyle(HighlightRowButtonStyle())
                                // Stagger runs across sections, not restarting per letter.
                                .entranceRow(index: sectionIndex + rowIndex)
                            }
                        } header: {
                            sectionHeader(section.letter)
                        }
                    }

                    if showsSkeleton {
                        SkeletonChatList(count: 8)
                    } else if let error = messaging.contactsError,
                              messaging.contacts.isEmpty,
                              messaging.incomingRequests.isEmpty
                    {
                        // Nothing loaded *and* the load failed — don't claim the roster is empty.
                        ListLoadErrorView(
                            title: "Can't load contacts",
                            message: error,
                            retry: { await messaging.refreshContacts(force: true) }
                        )
                    } else if sections.isEmpty && messaging.incomingRequests.isEmpty {
                        emptyState
                    }

                    shareFooter

                    Color.clear.frame(height: 16)
                }
                .animation(Motion.standard, value: sortAscending)
                .animation(Motion.standard, value: messaging.incomingRequests.map(\.id))
                .animation(Motion.fade, value: showsSkeleton)
                .animation(Motion.fade, value: messaging.contactsError)
            }
            .listEntranceHost(resetOn: messaging.contacts.isEmpty)
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
            Button {
                Task { await messaging.rejectRequest(request) }
            } label: {
                Text("Reject")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.danger)
                    .padding(.vertical, 10)
                    .contentShape(Rectangle())
            }
            .pressable(scale: 0.9)

            Button {
                // Success tick on accept — the row then animates out of the Pending section.
                Haptics.notification(.success)
                Task { await messaging.acceptRequest(request) }
            } label: {
                Text("Accept")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .padding(.vertical, 10)
                    .contentShape(Rectangle())
            }
            .pressable(scale: 0.9, haptic: nil)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .transition(.opacity.combined(with: .move(edge: .top)))
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
            Image(systemName: "person.2.fill")
                .font(.system(size: 32, weight: .semibold))
                .foregroundStyle(Theme.accent.opacity(0.85))
                .symbolEffect(.bounce, options: .nonRepeating)
                .padding(.bottom, 6)
            Text("No contacts yet")
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
        .transition(.opacity.combined(with: .offset(y: 8)))
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
                    .foregroundStyle(Theme.accent)
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
            }
            .pressable(scale: 0.95)
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
