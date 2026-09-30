import SafariServices
import UIKit

/// Opens web links the way Telegram does by default: in an in-app browser sheet.
///
/// Human: `SFSafariViewController` runs out of process and keeps its own cookie jar — Shroud
/// can't read the page and the page can't see Shroud. `mailto:` goes to the system. Nothing
/// else is ever opened: the link detector only produces `http`, `https` and `mailto`.
/// Agent: CALLS UIKit presentation on the foreground scene's top-most view controller; no
/// network access of its own, no logging of URLs.
enum InAppBrowser {
    /// Opens `url`. Returns false when it is not a scheme Shroud opens.
    @discardableResult
    static func open(_ url: URL) -> Bool {
        switch url.scheme?.lowercased() {
        case "http", "https":
            guard let presenter = topViewController() else {
                UIApplication.shared.open(url)
                return true
            }
            let configuration = SFSafariViewController.Configuration()
            configuration.barCollapsingEnabled = true
            let browser = SFSafariViewController(url: url, configuration: configuration)
            browser.dismissButtonStyle = .close
            presenter.present(browser, animated: true)
            return true
        case "mailto":
            UIApplication.shared.open(url)
            return true
        default:
            return false
        }
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        guard var top = scene?.keyWindow?.rootViewController else { return nil }
        while let presented = top.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }
        return top
    }
}
