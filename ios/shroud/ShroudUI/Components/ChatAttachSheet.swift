import SwiftUI

/// Bottom attach tray — maps to `Attach Sheet` in `Conversation — Attach Open`.
struct ChatAttachSheet: View {
    var onSelect: (ChatAttachOption) -> Void
    var onCancel: () -> Void

    private let row1: [ChatAttachOption] = [.camera, .photos, .file, .location]
    private let row2: [ChatAttachOption] = [.contact, .music, .gift, .stickers]

    var body: some View {
        VStack(spacing: 14) {
            Capsule()
                .fill(Color(red: 0.847, green: 0.847, blue: 0.863))
                .frame(width: 36, height: 5)
                .padding(.top, 4)

            // Photo strip placeholder (media library wiring later).
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("RECENTS")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Theme.textSecondary)
                    Spacer()
                    Button {
                        onSelect(.photos)
                    } label: {
                        HStack(spacing: 2) {
                            Text("All Photos")
                                .font(.system(size: 13, weight: .semibold))
                            Image(systemName: "chevron.right")
                                .font(.system(size: 11, weight: .semibold))
                        }
                        .foregroundStyle(Theme.accent)
                    }
                    .buttonStyle(.plain)
                }

                HStack(spacing: 8) {
                    ForEach(0 ..< 4, id: \.self) { index in
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .fill(Theme.backgroundGrouped)
                            .frame(width: 96, height: 96)
                            .overlay {
                                Image(systemName: index == 0 ? "photo.fill" : "photo")
                                    .font(.system(size: 22))
                                    .foregroundStyle(Theme.textSecondary.opacity(0.55))
                            }
                            .onTapGesture { onSelect(.photos) }
                    }
                    Spacer(minLength: 0)
                }
            }

            optionRow(row1)
            optionRow(row2)

            Button(action: onCancel) {
                Text("Cancel")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Theme.backgroundGrouped)
                    .clipShape(Capsule())
            }
            .buttonStyle(.plain)
            .padding(.bottom, 4)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
        .background(
            Color(red: 0.984, green: 0.984, blue: 0.992)
                .clipShape(UnevenRoundedRectangle(
                    topLeadingRadius: 20,
                    bottomLeadingRadius: 0,
                    bottomTrailingRadius: 0,
                    topTrailingRadius: 20,
                    style: .continuous
                ))
        )
    }

    private func optionRow(_ options: [ChatAttachOption]) -> some View {
        HStack(spacing: 8) {
            ForEach(options) { option in
                Button {
                    onSelect(option)
                } label: {
                    VStack(spacing: 6) {
                        ZStack {
                            Circle()
                                .fill(option.circleFill)
                                .frame(width: 52, height: 52)
                            Image(systemName: option.systemImage)
                                .font(.system(size: 20, weight: .semibold))
                                .foregroundStyle(option.iconColor)
                        }
                        Text(option.title)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(Theme.textPrimary)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

enum ChatAttachOption: String, Identifiable, CaseIterable {
    case camera, photos, file, location, contact, music, gift, stickers

    var id: String { rawValue }

    var title: String {
        switch self {
        case .camera: "Camera"
        case .photos: "Photos"
        case .file: "File"
        case .location: "Location"
        case .contact: "Contact"
        case .music: "Music"
        case .gift: "Gift"
        case .stickers: "Stickers"
        }
    }

    var systemImage: String {
        switch self {
        case .camera: "camera.fill"
        case .photos: "photo.fill"
        case .file: "doc.fill"
        case .location: "mappin.and.ellipse"
        case .contact: "person.fill"
        case .music: "music.note"
        case .gift: "gift.fill"
        case .stickers: "face.smiling.fill"
        }
    }

    var circleFill: Color {
        switch self {
        case .camera: Color(red: 0.925, green: 0.925, blue: 0.988)
        case .photos: Color(red: 0.902, green: 0.969, blue: 0.925)
        case .file: Color(red: 0.894, green: 0.945, blue: 0.988)
        case .location: Color(red: 0.996, green: 0.937, blue: 0.890)
        case .contact: Color(red: 0.988, green: 0.906, blue: 0.929)
        case .music: Color(red: 0.953, green: 0.910, blue: 0.992)
        case .gift: Color(red: 0.992, green: 0.945, blue: 0.863)
        case .stickers: Color(red: 0.886, green: 0.965, blue: 0.965)
        }
    }

    var iconColor: Color {
        switch self {
        case .camera: Theme.accent
        case .photos: Color(red: 0.184, green: 0.659, blue: 0.357)
        case .file: Color(red: 0.180, green: 0.561, blue: 0.878)
        case .location: Color(red: 0.969, green: 0.420, blue: 0.110)
        case .contact: Color(red: 0.902, green: 0.290, blue: 0.447)
        case .music: Color(red: 0.608, green: 0.290, blue: 0.902)
        case .gift: Color(red: 0.902, green: 0.604, blue: 0.110)
        case .stickers: Color(red: 0.059, green: 0.639, blue: 0.639)
        }
    }
}

#Preview {
    Color.black.opacity(0.3).ignoresSafeArea()
        .safeAreaInset(edge: .bottom) {
            ChatAttachSheet(onSelect: { _ in }, onCancel: {})
        }
}
