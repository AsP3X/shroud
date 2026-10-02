package de.corespace.shroud.upstub;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Base64;
import java.util.UUID;

/**
 * Distributor side of AND_3 for instrumented tests. REGISTER answers with a local ntfy topic
 * ({@code http://localhost:<port>/up<random>?up=1}). {@link ForwarderService} subscribes to that
 * topic and forwards each message. INJECT still delivers a payload as MESSAGE.
 */
public final class DistributorReceiver extends BroadcastReceiver {
    static final String PREFS = "upstub";
    static final String KEY_TOKEN = "token";
    static final String KEY_PACKAGE = "package";
    static final String KEY_TOPIC = "topic";
    static final String KEY_PORT = "port";
    static final String KEY_LAST_ID = "lastId";
    static final String KEY_SEEN = "seen";
    static final int DEFAULT_PORT = 2586;
    static final String ACTION_INJECT = "de.corespace.shroud.upstub.INJECT";
    static final String ACTION_CONFIG = "de.corespace.shroud.upstub.CONFIG";
    static final String MESSAGE_RECEIVER = "de.corespace.shroud.core.push.unifiedpush.UnifiedPushReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (ACTION_CONFIG.equals(action)) {
            int port = intent.getIntExtra("port", -1);
            if (port > 0 && port < 65536) prefs.edit().putInt(KEY_PORT, port).commit();
            ForwarderService.start(context);
            return;
        }
        if ("org.unifiedpush.android.distributor.REGISTER".equals(action)) {
            String token = intent.getStringExtra("token");
            String app = senderPackage(intent);
            if (token == null || token.isEmpty() || app == null) return;
            String topic = prefs.getString(KEY_TOPIC, null);
            String savedToken = prefs.getString(KEY_TOKEN, null);
            if (topic == null || topic.isEmpty() || savedToken == null || !savedToken.equals(token)) {
                topic = "up" + UUID.randomUUID().toString().replace("-", "");
            }
            int port = prefs.getInt(KEY_PORT, DEFAULT_PORT);
            prefs.edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_PACKAGE, app)
                .putString(KEY_TOPIC, topic)
                .putInt(KEY_PORT, port)
                .commit();
            ForwarderService.start(context);
            Intent reply = new Intent("org.unifiedpush.android.connector.NEW_ENDPOINT");
            reply.setPackage(app);
            reply.putExtra("token", token);
            reply.putExtra("endpoint", endpoint(port, topic));
            reply.putExtra("id", "1");
            context.sendBroadcast(reply);
            return;
        }
        if ("org.unifiedpush.android.distributor.UNREGISTER".equals(action)) {
            String token = intent.getStringExtra("token");
            String saved = prefs.getString(KEY_TOKEN, null);
            String app = prefs.getString(KEY_PACKAGE, null);
            if (token == null || saved == null || !saved.equals(token) || app == null) return;
            int port = prefs.getInt(KEY_PORT, DEFAULT_PORT);
            prefs.edit().clear().putInt(KEY_PORT, port).commit();
            Intent reply = new Intent("org.unifiedpush.android.connector.UNREGISTERED");
            reply.setPackage(app);
            reply.putExtra("token", token);
            context.sendBroadcast(reply);
            return;
        }
        if (ACTION_INJECT.equals(action)) {
            String token = prefs.getString(KEY_TOKEN, null);
            String app = prefs.getString(KEY_PACKAGE, null);
            byte[] bytes = payload(intent);
            if (token == null || app == null || bytes == null) return;
            deliver(context, app, token, bytes, UUID.randomUUID().toString());
        }
    }

    static String endpoint(int port, String topic) {
        return "http://localhost:" + port + "/" + topic + "?up=1";
    }

    /** MESSAGE to Shroud, including when the app was force-stopped. */
    static void deliver(Context context, String app, String token, byte[] bytes, String id) {
        Intent reply = new Intent("org.unifiedpush.android.connector.MESSAGE");
        reply.setClassName(app, MESSAGE_RECEIVER);
        reply.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        reply.putExtra("token", token);
        reply.putExtra("bytesMessage", bytes);
        reply.putExtra("id", id);
        context.sendBroadcast(reply);
    }

    private String senderPackage(Intent intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            String from = getSentFromPackage();
            if (from != null && !from.isEmpty()) return from;
        }
        PendingIntent pi = pendingIdentity(intent);
        return pi == null ? null : pi.getCreatorPackage();
    }

    private static PendingIntent pendingIdentity(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra("pi", PendingIntent.class);
        return intent.getParcelableExtra("pi");
    }

    private static byte[] payload(Intent intent) {
        byte[] raw = intent.getByteArrayExtra("bytes");
        if (raw != null) return raw;
        String text = intent.getStringExtra("payload");
        if (text == null || text.isEmpty()) return null;
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException ignored) {
            try {
                return Base64.getUrlDecoder().decode(text);
            } catch (IllegalArgumentException bad) {
                return null;
            }
        }
    }
}
