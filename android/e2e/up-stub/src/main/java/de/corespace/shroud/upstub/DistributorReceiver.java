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
 * Distributor side of AND_3 for instrumented tests. No network: the endpoint is a name that
 * does not resolve, and an injected payload is handed back as MESSAGE.
 */
public final class DistributorReceiver extends BroadcastReceiver {
    static final String PREFS = "upstub";
    static final String KEY_TOKEN = "token";
    static final String KEY_PACKAGE = "package";
    static final String ENDPOINT = "https://up-stub.invalid/push/test";
    static final String ACTION_INJECT = "de.corespace.shroud.upstub.INJECT";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if ("org.unifiedpush.android.distributor.REGISTER".equals(action)) {
            String token = intent.getStringExtra("token");
            String app = senderPackage(intent);
            if (token == null || token.isEmpty() || app == null) return;
            prefs.edit().putString(KEY_TOKEN, token).putString(KEY_PACKAGE, app).apply();
            Intent reply = new Intent("org.unifiedpush.android.connector.NEW_ENDPOINT");
            reply.setPackage(app);
            reply.putExtra("token", token);
            reply.putExtra("endpoint", ENDPOINT);
            reply.putExtra("id", "1");
            context.sendBroadcast(reply);
            return;
        }
        if ("org.unifiedpush.android.distributor.UNREGISTER".equals(action)) {
            String token = intent.getStringExtra("token");
            String saved = prefs.getString(KEY_TOKEN, null);
            String app = prefs.getString(KEY_PACKAGE, null);
            if (token == null || saved == null || !saved.equals(token) || app == null) return;
            prefs.edit().clear().apply();
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
            Intent reply = new Intent("org.unifiedpush.android.connector.MESSAGE");
            reply.setPackage(app);
            reply.putExtra("token", token);
            reply.putExtra("bytesMessage", bytes);
            reply.putExtra("id", UUID.randomUUID().toString());
            context.sendBroadcast(reply);
        }
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
