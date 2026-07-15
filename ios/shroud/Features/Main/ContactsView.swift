import SwiftUI

/// Contacts directory — maps to `Contacts` in `iOS-App.pen`.
/// Sticky large title collapses into a compact bar title while scrolling.
struct ContactsView: View {
    @State private var searchText = ""

    private var filtered: [ContactListItem] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return MainSampleData.contacts }
        return MainSampleData.contacts.filter {
            $0.name.localizedCaseInsensitiveContains(query)
                || $0.status.localizedCaseInsensitiveContains(query)
        }
    }

    private var sections: [(letter: String, items: [ContactListItem])] {
        let grouped = Dictionary(grouping: filtered, by: \.sectionKey)
        return grouped.keys.sorted().map { key in
            (key, (grouped[key] ?? []).sorted { $0.name < $1.name })
        }
    }

    var body: some View {
        MainScrollScreen(title: "Contacts", collapsesTitle: true) {
            Button("Sort") {}
                .font(.system(size: 16))
                .foregroundStyle(Theme.accent)
        } navTrailing: {
            Button {} label: {
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
                ForEach(sections, id: \.letter) { section in
                    Section {
                        ForEach(section.items) { contact in
                            Button {} label: {
                                ChatRowView(
                                    title: contact.name,
                                    subtitle: contact.status,
                                    subtitleAccent: contact.isOnline,
                                    avatarGradient: contact.gradient
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    } header: {
                        Text(section.letter)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(Theme.textSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 3)
                            .background(.ultraThinMaterial)
                    }
                }

                if sections.isEmpty {
                    VStack(spacing: 8) {
                        Text("No contacts found")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(Theme.textPrimary)
                        Text("Try a different search.")
                            .font(.system(size: 14))
                            .foregroundStyle(Theme.textSecondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 48)
                }

                Color.clear.frame(height: 16)
            }
        }
        .background(Theme.background)
    }
}

#Preview {
    ContactsView()
}
