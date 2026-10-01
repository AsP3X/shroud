-- Android pushes ride Web Push: which kind of client holds a Web Push subscription.
-- Human: The Android app is reached through UnifiedPush — a distributor app the user installed
-- (ntfy, Sunup, …) hands it a Web Push endpoint, and the server sends exactly what it sends a
-- browser: RFC 8291 ciphertext with its VAPID key. Two things differ. A distributor endpoint can
-- be any host, so Android endpoints pass their own host policy (routes::push). And an app,
-- unlike a browser, need not show a notification for every push, so Android also gets call
-- rings while it looks in front, `call_ended`, and `read` (push::mod). No Google service is
-- involved: Google's FCM endpoints are refused for Android subscriptions.
-- Agent: DB forward-only. Existing rows are browsers. WRITTEN by routes::push::put_web_subscription;
-- READ by push::PushService targets and RemovedDeviceWake.

ALTER TABLE web_push_subscriptions
    ADD COLUMN client TEXT NOT NULL DEFAULT 'browser'
        CONSTRAINT web_push_subscriptions_client_check CHECK (client IN ('browser', 'android'));
