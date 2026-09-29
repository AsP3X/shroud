import Photos
import SwiftUI
import UIKit

/// Bottom attach tray — maps to `Attach Sheet` in `Conversation — Attach Open`.
struct ChatAttachSheet: View {
    var onSelect: (ChatAttachOption) -> Void
    var onCancel: () -> Void
    /// Called with the tapped photo once its **original** file has been fetched.
    var onPickImage: ((PickedPhoto) -> Void)? = nil

    /// A tile in the recents strip: a cheap thumbnail plus the asset it came from.
    ///
    /// Human: The thumbnail is 192 px — fine to show, catastrophic to send. Tapping goes back
    /// to the asset for the original file, so "Original" quality means the real photo.
    private struct RecentPhoto: Identifiable, Sendable {
        let id: String
        let thumbnail: UIImage
    }

    @State private var recentPhotos: [RecentPhoto] = []
    @State private var photoAccessDenied = false
    /// Set once the library has answered, so an empty library reads as empty, not as loading.
    @State private var recentsLoaded = false
    /// Asset whose original is being fetched (may be an iCloud download).
    @State private var loadingAssetID: String?
    /// The original fetch in flight. Cancelled when the user leaves or picks something else,
    /// so a slow iCloud download can't open compose with a photo they abandoned.
    @State private var pickTask: Task<Void, Never>?

    @Environment(\.colorScheme) private var colorScheme

    private let row1: [ChatAttachOption] = [.camera, .photos, .file, .location]
    private let row2: [ChatAttachOption] = [.contact, .music, .gift, .stickers]

    var body: some View {
        VStack(spacing: 14) {
            Capsule()
                .fill(Theme.separator)
                .frame(width: 36, height: 5)
                .padding(.top, 4)

            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("RECENTS")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Theme.textSecondary)
                        .accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button {
                        pickTask?.cancel()
                        onSelect(.photos)
                    } label: {
                        HStack(spacing: 2) {
                            Text("All Photos")
                                .font(.system(size: 13, weight: .semibold))
                            Image(systemName: "chevron.right")
                                .font(.system(size: 11, weight: .semibold))
                                .accessibilityHidden(true)
                        }
                        .foregroundStyle(Theme.accent)
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                    }
                    .pressable(scale: 0.94)
                }

                if photoAccessDenied {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Allow Photos access in Settings to see recent images here.")
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.textSecondary)
                        Button {
                            if let url = URL(string: UIApplication.openSettingsURLString) {
                                UIApplication.shared.open(url)
                            }
                        } label: {
                            Text("Open Settings")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(Theme.accent)
                                .padding(.vertical, 4)
                                .contentShape(Rectangle())
                        }
                        .pressable(scale: 0.94)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 12)
                } else if !recentsLoaded {
                    // Shimmering tiles instead of spinners — the strip's shape is already known.
                    HStack(spacing: 8) {
                        ForEach(0 ..< 4, id: \.self) { _ in
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(Theme.backgroundGrouped)
                                .frame(width: 96, height: 96)
                        }
                        Spacer(minLength: 0)
                    }
                    .shimmering()
                    .transition(.opacity)
                } else if recentPhotos.isEmpty {
                    // Empty library, or Limited access with nothing selected — "All Photos" still
                    // reaches the whole library.
                    Text("No recent photos")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 12)
                        .transition(.opacity)
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(Array(recentPhotos.enumerated()), id: \.element.id) { index, photo in
                                Button {
                                    if onPickImage != nil {
                                        pickOriginal(photo)
                                    } else {
                                        onSelect(.photos)
                                    }
                                } label: {
                                    Image(uiImage: photo.thumbnail)
                                        .resizable()
                                        .scaledToFill()
                                        .frame(width: 96, height: 96)
                                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                        .overlay {
                                            if loadingAssetID == photo.id {
                                                ZStack {
                                                    Color.black.opacity(0.35)
                                                    ProgressView().tint(.white)
                                                }
                                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                            }
                                        }
                                }
                                .pressable(scale: 0.93, dimming: 0.12)
                                // One fetch at a time — an iCloud original can take a moment.
                                .disabled(loadingAssetID != nil)
                                .accessibilityLabel("Recent photo \(index + 1) of \(recentPhotos.count)")
                                .accessibilityValue(loadingAssetID == photo.id ? "Loading" : "")
                                .entranceRow(index: index)
                            }
                        }
                    }
                    .transition(.opacity)
                }
            }
            .animation(Motion.fade, value: recentPhotos.isEmpty)
            .animation(Motion.fade, value: recentsLoaded)
            .animation(Motion.fade, value: loadingAssetID)

            optionRow(row1, startIndex: 0)
            optionRow(row2, startIndex: row1.count)

            Button {
                pickTask?.cancel()
                onCancel()
            } label: {
                Text("Cancel")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .frame(maxWidth: .infinity)
                    .frame(height: 50)
                    .background(Theme.backgroundGrouped)
                    .clipShape(Capsule())
            }
            .pressable(scale: 0.975, dimming: 0.06)
            .padding(.bottom, 4)
        }
        // Tiles ripple in behind the sheet's own presentation.
        .listEntranceHost(resetOn: false)
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
        .background(
            Theme.background
                .clipShape(UnevenRoundedRectangle(
                    topLeadingRadius: 20,
                    bottomLeadingRadius: 0,
                    bottomTrailingRadius: 0,
                    topTrailingRadius: 20,
                    style: .continuous
                ))
        )
        .task {
            await loadRecentPhotos()
        }
        // Swipe-to-dismiss skips Cancel; drop a fetch that is still running.
        .onDisappear { pickTask?.cancel() }
    }

    /// `startIndex` continues the entrance stagger across both rows.
    private func optionRow(_ options: [ChatAttachOption], startIndex: Int) -> some View {
        HStack(spacing: 8) {
            ForEach(Array(options.enumerated()), id: \.element.id) { index, option in
                Button {
                    pickTask?.cancel()
                    onSelect(option)
                } label: {
                    VStack(spacing: 6) {
                        ZStack {
                            // The light pastels would be bright discs on the black sheet; in dark
                            // mode a dim wash of the icon's own colour stands in.
                            Circle()
                                .fill(colorScheme == .dark ? option.iconColor.opacity(0.2) : option.circleFill)
                                .frame(width: 52, height: 52)
                            Image(systemName: option.systemImage)
                                .font(.system(size: 20, weight: .semibold))
                                .foregroundStyle(option.iconColor)
                        }
                        // The title alone names the button.
                        .accessibilityHidden(true)
                        Text(option.title)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(Theme.textPrimary)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
                }
                .pressable(scale: 0.9)
                .entranceRow(index: startIndex + index)
            }
        }
    }

    @MainActor
    private func loadRecentPhotos() async {
        let status = await requestPhotoAccess()
        guard status == .authorized || status == .limited else {
            photoAccessDenied = true
            recentPhotos = []
            return
        }
        photoAccessDenied = false

        let photos: [RecentPhoto] = await Task.detached(priority: .userInitiated) {
            let options = PHFetchOptions()
            options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
            options.fetchLimit = 12
            let result = PHAsset.fetchAssets(with: .image, options: options)
            guard result.count > 0 else { return [] }

            let manager = PHImageManager.default()
            let requestOptions = PHImageRequestOptions()
            requestOptions.deliveryMode = .highQualityFormat
            requestOptions.resizeMode = .fast
            requestOptions.isNetworkAccessAllowed = true
            requestOptions.isSynchronous = true

            var out: [RecentPhoto] = []
            let target = CGSize(width: 192, height: 192)
            result.enumerateObjects { asset, _, stop in
                let identifier = asset.localIdentifier
                manager.requestImage(
                    for: asset,
                    targetSize: target,
                    contentMode: .aspectFill,
                    options: requestOptions
                ) { image, _ in
                    if let image {
                        out.append(RecentPhoto(id: identifier, thumbnail: image))
                    }
                }
                if out.count >= 12 { stop.pointee = true }
            }
            return out
        }.value

        recentPhotos = photos
        recentsLoaded = true
    }

    /// Fetches the tapped asset's original file before handing it to compose.
    private func pickOriginal(_ photo: RecentPhoto) {
        guard loadingAssetID == nil else { return }
        loadingAssetID = photo.id
        pickTask = Task {
            let picked = await Self.originalPhoto(localIdentifier: photo.id, fallback: photo.thumbnail)
            loadingAssetID = nil
            // Cancelled while it loaded (Cancel, swipe-away, another option): drop the photo.
            guard !Task.isCancelled else { return }
            onPickImage?(picked)
        }
    }

    /// The asset's current file bytes — edits included, iCloud originals downloaded on demand.
    /// Falls back to the thumbnail only when the library refuses to hand anything back.
    private static func originalPhoto(localIdentifier: String, fallback: UIImage) async -> PickedPhoto {
        // Fetch *and* downsample off the main thread — the original can be 48 MP.
        let loaded: (data: Data, preview: UIImage)? = await Task.detached(priority: .userInitiated) {
            guard let asset = PHAsset.fetchAssets(
                withLocalIdentifiers: [localIdentifier],
                options: nil
            ).firstObject else { return nil }

            let options = PHImageRequestOptions()
            options.version = .current
            options.deliveryMode = .highQualityFormat
            options.isNetworkAccessAllowed = true
            options.isSynchronous = true

            var out: Data?
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, _, _, _ in
                out = data
            }
            guard let data = out, let preview = MediaCrypto.previewImage(from: data, maxEdge: 2048) else {
                return nil
            }
            return (data, preview)
        }.value

        guard let loaded else { return PickedPhoto(image: fallback) }
        return PickedPhoto(preview: loaded.preview, source: .fileData(loaded.data))
    }

    private func requestPhotoAccess() async -> PHAuthorizationStatus {
        let current = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if current == .notDetermined {
            return await withCheckedContinuation { cont in
                PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
                    cont.resume(returning: status)
                }
            }
        }
        return current
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
        case .camera: Theme.accentSoft
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
