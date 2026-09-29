import SwiftUI

/// Add a contact via QR scan, share link, share code, or username.
struct AddContactSheet: View {
    @Environment(MessagingController.self) private var messaging
    @Environment(\.dismiss) private var dismiss

    /// Called with the confirmation to show ("Request sent to jane") just before the sheet
    /// closes; the presenter owns the toast, since the sheet is gone by then.
    var onAdded: (String) -> Void = { _ in }

    @State private var inviteText = ""
    @State private var errorMessage: String?
    @State private var isAdding = false
    @State private var showScanner = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button {
                        showScanner = true
                    } label: {
                        Label("Scan QR code", systemImage: "qrcode.viewfinder")
                            .font(.system(size: 16, weight: .semibold))
                    }
                } footer: {
                    Text("Scan your contact’s Shroud QR code.")
                }

                Section {
                    TextField("Code, link, or username", text: $inviteText, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .lineLimit(2 ... 4)
                        .font(.system(.body, design: .monospaced))
                } footer: {
                    Text("Paste a share link, enter their short share code, username, or user ID.")
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .foregroundStyle(Theme.danger)
                            .font(.system(size: 14))
                    }
                }
            }
            .navigationTitle("Add Contact")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(isAdding ? "Adding…" : "Add") {
                        Task { await submit(inviteText) }
                    }
                    .disabled(isAdding || inviteText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .fullScreenCover(isPresented: $showScanner) {
                QRCodeScannerView(
                    onCode: { value in
                        showScanner = false
                        inviteText = value
                        Task { await submit(value) }
                    },
                    onCancel: {
                        showScanner = false
                    }
                )
                .ignoresSafeArea()
                // Always-dark camera surface: keeps the status bar light over the black
                // preview in light mode, as the other full-screen dark covers do.
                .preferredColorScheme(.dark)
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func submit(_ raw: String) async {
        isAdding = true
        errorMessage = nil
        let outcome = await messaging.addContact(fromInvite: raw)
        isAdding = false
        switch outcome {
        case let .failed(message):
            errorMessage = message
            Haptics.notification(.error)
            // The red section appears silently otherwise.
            AccessibilityNotification.Announcement(message).post()
        case let .requested(username):
            Haptics.notification(.success)
            onAdded("Request sent to \(username)")
            dismiss()
        case let .added(username):
            // They had already asked us: the server accepted both, and the contact is in.
            Haptics.notification(.success)
            onAdded("\(username) added")
            dismiss()
        }
    }
}
