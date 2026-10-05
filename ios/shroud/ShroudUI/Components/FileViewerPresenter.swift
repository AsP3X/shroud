import QuickLook
import SwiftUI
import UIKit

/// Hands an opened file to Shroud's PDF viewer, Quick Look or the share sheet, and takes it back.
///
/// Human: The plaintext under `tmp/shroud-file-{id}/` exists only while one of these has it:
/// the close callback removes it the moment the preview or the sheet goes away. Locking the
/// chats closes both first (`MessagingController.lockSensitiveMemory`), so a file never stays
/// open over the lock screen.
/// Agent: Presents over the top-most view controller, like the photo viewer's share sheet. One
/// thing at a time: opening something closes what was open before.
@MainActor
final class FileViewerPresenter: NSObject {
    static let shared = FileViewerPresenter()

    private var previewURL: URL?
    /// The message whose file is open.
    private var messageID: UUID?
    private weak var preview: QLPreviewController?
    private weak var pdfViewer: UIViewController?
    private weak var activity: UIActivityViewController?
    /// The open thing's cleanup, run exactly once.
    private var onClose: (() -> Void)?
    /// Bumped per presentation, so a late callback of a closed one can't close the next.
    private var generation = 0

    /// Quick Look on `url`; `onClose` runs once the preview is gone.
    func preview(_ url: URL, messageID: UUID, onClose: @escaping () -> Void) {
        dismissAll()
        self.messageID = messageID
        guard let presenter = Self.topViewController() else {
            onClose()
            return
        }
        let controller = QLPreviewController()
        generation += 1
        previewURL = url
        self.onClose = onClose
        controller.dataSource = self
        controller.delegate = self
        preview = controller
        presenter.present(controller, animated: true)
    }

    /// Shroud's own PDF viewer on `url` (`docs/file-sharing.md` §10.2); `onClose` runs once it
    /// is gone.
    ///
    /// Human: The plaintext is removed only after the viewer has slid away, so PDFKit never
    /// loses the file under a page it is still drawing.
    func pdf(_ url: URL, messageID: UUID, title: String, onClose: @escaping () -> Void) {
        dismissAll()
        self.messageID = messageID
        guard let presenter = Self.topViewController() else {
            onClose()
            return
        }
        generation += 1
        let presentation = generation
        self.onClose = onClose
        let model = PDFViewerModel(url: url, messageID: messageID, title: title)
        let controller = UIHostingController(rootView: PDFViewerScreen(model: model) { [weak self] in
            guard let self, self.generation == presentation, let viewer = self.pdfViewer else { return }
            viewer.dismiss(animated: true) { [weak self] in
                guard let self, self.generation == presentation else { return }
                self.finish()
            }
        })
        controller.modalPresentationStyle = .fullScreen
        pdfViewer = controller
        presenter.present(controller, animated: true)
    }

    /// The share sheet for `url`, anchored on iPad to `sourceRect` (window coordinates, the
    /// bubble's frame) or the screen's lower middle.
    func share(_ url: URL, messageID: UUID, from sourceRect: CGRect?, onClose: @escaping () -> Void) {
        dismissAll()
        self.messageID = messageID
        guard let presenter = Self.topViewController() else {
            onClose()
            return
        }
        let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        generation += 1
        let presentation = generation
        self.onClose = onClose
        controller.completionWithItemsHandler = { [weak self] _, _, _, _ in
            guard let self, self.generation == presentation else { return }
            self.finish()
        }
        if let popover = controller.popoverPresentationController {
            popover.sourceView = presenter.view
            if let sourceRect, !sourceRect.isEmpty {
                popover.sourceRect = presenter.view.convert(sourceRect, from: nil)
            } else {
                popover.sourceRect = CGRect(
                    x: presenter.view.bounds.midX,
                    y: presenter.view.bounds.maxY - 80,
                    width: 1,
                    height: 1
                )
            }
        }
        activity = controller
        presenter.present(controller, animated: true)
    }

    /// Closes the preview or the sheet, if one is up, and removes its plaintext.
    func dismissAll() {
        if let preview, preview.presentingViewController != nil {
            preview.dismiss(animated: false)
        }
        if let activity, activity.presentingViewController != nil {
            activity.dismiss(animated: false)
        }
        if let pdfViewer, pdfViewer.presentingViewController != nil {
            pdfViewer.dismiss(animated: false)
        }
        finish()
    }

    /// Closes whatever is open of `messageID` (deleted for everyone, or deleted here).
    func dismiss(messageID: UUID) {
        guard self.messageID == messageID else { return }
        dismissAll()
    }

    private func finish() {
        generation += 1
        messageID = nil
        let close = onClose
        onClose = nil
        previewURL = nil
        preview = nil
        pdfViewer = nil
        activity = nil
        close?()
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first,
              let root = scene.windows.first(where: \.isKeyWindow)?.rootViewController
        else { return nil }
        var top = root
        while let presented = top.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }
        return top
    }
}

extension FileViewerPresenter: QLPreviewControllerDataSource, QLPreviewControllerDelegate {
    func numberOfPreviewItems(in controller: QLPreviewController) -> Int {
        previewURL == nil ? 0 : 1
    }

    func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> any QLPreviewItem {
        (previewURL ?? URL(fileURLWithPath: "/dev/null")) as NSURL
    }

    func previewControllerDidDismiss(_ controller: QLPreviewController) {
        guard controller === preview, controller.dataSource === self else { return }
        finish()
    }
}
