import UIKit

/// UIKit app delegate for APNs device token callbacks and the keyboard policy.
final class AppDelegate: NSObject, UIApplicationDelegate {
    /// Settings → Privacy and Security → "Only Apple keyboards".
    func application(
        _ application: UIApplication,
        shouldAllowExtensionPointIdentifier extensionPointIdentifier: UIApplication.ExtensionPointIdentifier
    ) -> Bool {
        extensionPointIdentifier != .keyboard || !SecurityPreferences.blocksThirdPartyKeyboards
    }

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Before launch finishes: a tap on a notification that launched the app is delivered
        // to the delegate right after this returns.
        PushNotificationService.shared.install()
        return true
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        Task { @MainActor in
            PushNotificationService.shared.didRegisterForRemoteNotifications(deviceToken: deviceToken)
        }
    }

    /// Silent pushes. The only one the server sends is `device_removed`.
    /// The completion-handler form: the async one completes off the main thread.
    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        guard DeviceRemovalWake.isRemoval(userInfo) else {
            completionHandler(.noData)
            return
        }
        // The payload's `reason` is passed through. It does not skip the `/auth/me` confirm.
        let reason = userInfo["reason"] as? String
        Task { @MainActor in
            completionHandler(await DeviceRemovalWake.handle(pushReason: reason))
        }
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        Task { @MainActor in
            PushNotificationService.shared.didFailToRegisterForRemoteNotifications(error: error)
        }
    }
}
