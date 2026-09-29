import SwiftUI

/// Contacts directory — live API (requests + list).
/// Opens chats with a standard **horizontal push / swipe** transition.
struct ContactsView: View {
    @Environment(MessagingController.self) private var messaging
    @Environment(SessionController.self) private var session
    @Environment(\.isTabBarSearchActive) private var isTabBarSearchActive

    @Binding var path: [ChatRoute]
    /// Owned by `MainTabView` so the tab bar's search field filters this list too.
    @Binding var searchText: String
    @State private var showAdd = false
    @State private var showMyQR = false
    @State private var sortAscending = true
    /// Pending requests with an Accept or Reject on the wire; their buttons wait for it.
    @State private var respondingRequestIDs: Set<UUID> = []
    @State private var toast: Toast?

    init(path: Binding<[ChatRoute]> = .constant([]), searchText: Binding<String> = .constant("")) {
        _path = path
        _searchText = searchText
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

    private var isSearching: Bool {
        !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
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
                GlassBarButton(shape: .capsule) {
                    // Sections re-sort with a spring so the letters visibly travel.
                    withAnimation(Motion.standard) {
                        sortAscending.toggle()
                    }
                } label: {
                    // GlassBarButton's capsule supplies the label font and padding.
                    Text(sortAscending ? "A–Z" : "Z–A")
                        .contentTransition(.opacity)
                }
                .accessibilityLabel(sortAscending ? "Sorted A to Z" : "Sorted Z to A")
                .accessibilityHint("Reverses the order")
            } navTrailing: {
                // Two related actions fused into one capsule, as the system toolbar groups them.
                GlassBarGroup {
                    GlassBarButton(systemImage: "qrcode") {
                        showMyQR = true
                    }
                    .accessibilityLabel("My QR code")

                    GlassBarButton(systemImage: "person.badge.plus") {
                        showAdd = true
                    }
                    .accessibilityLabel("Add contact")
                }
            } accessory: {
                if !isTabBarSearchActive {
                    SearchField(text: $searchText)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 10)
                        .transition(.opacity)
                }
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
                                        activity: messaging.peerActivity(for: contact.userId),
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
                // A sent request shows up nowhere in the list, so the toast is the only sign
                // that it went out.
                AddContactSheet(onAdded: { message in
                    toast = Toast(message, duration: .seconds(2.4))
                })
            }
            .sheet(isPresented: $showMyQR) {
                MyQRCodeSheet()
            }
            .toast($toast)
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
        let isResponding = respondingRequestIDs.contains(request.id)
        return HStack(spacing: 12) {
            AvatarView(initials: AvatarView.initials(for: name), gradient: AvatarView.gradient(for: name))
            VStack(alignment: .leading, spacing: 2) {
                // Handles have no spaces to wrap at, and the fallback is a 36-character id.
                Text(name)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text("wants to connect")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
            }
            .accessibilityElement(children: .combine)
            Spacer()
            Button {
                respond(to: request, accept: false)
            } label: {
                Text("Reject")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.danger)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .pressable(scale: 0.9)
            .disabled(isResponding)
            // The labels set their own colour, so a disabled button has to dim itself.
            .opacity(isResponding ? 0.4 : 1)

            Button {
                respond(to: request, accept: true)
            } label: {
                Text("Accept")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .pressable(scale: 0.9)
            .disabled(isResponding)
            .opacity(isResponding ? 0.4 : 1)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .animation(Motion.fade, value: isResponding)
        .transition(.opacity.combined(with: .move(edge: .top)))
    }

    /// Human: Accept or Reject once; both buttons wait while the answer is on its way. The
    /// success tick only comes when the server took it (the row then animates out of
    /// Pending); a failure keeps the row and says why.
    /// Agent: CALLS messaging.acceptRequest / rejectRequest; WRITES respondingRequestIDs
    /// and `toast`.
    private func respond(to request: ContactRequestDTO, accept: Bool) {
        guard respondingRequestIDs.insert(request.id).inserted else { return }
        Task {
            let error = if accept {
                await messaging.acceptRequest(request)
            } else {
                await messaging.rejectRequest(request)
            }
            respondingRequestIDs.remove(request.id)
            if let error {
                toast = .failure(error)
                Haptics.notification(.error)
            } else if accept {
                Haptics.notification(.success)
            }
        }
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(Theme.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 3)
            .background(.ultraThinMaterial)
            // VoiceOver's Headings rotor then jumps letter by letter.
            .accessibilityAddTraits(.isHeader)
    }

    /// "online" / "last seen …" / "offline"; "contact" until the server has told us anything.
    private func contactStatus(_ contact: ContactItemDTO) -> String {
        ChatListFormatting.presenceLabel(for: messaging.presenceByUser[contact.userId]) ?? "contact"
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "person.2.fill")
                .font(.system(size: 32, weight: .semibold))
                .foregroundStyle(Theme.accent.opacity(0.85))
                .symbolEffect(.bounce, options: .nonRepeating)
                .padding(.bottom, 6)
                // Decorative: the title says what's going on.
                .accessibilityHidden(true)
            // A search that misses must not tell a full roster it has no contacts.
            Text(isSearching ? "No matches" : "No contacts yet")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
            Text(isSearching ? "Try a different name." : "Scan a QR code or enter a share code to add someone.")
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
