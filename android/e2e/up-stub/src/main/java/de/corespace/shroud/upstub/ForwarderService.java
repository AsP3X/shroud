package de.corespace.shroud.upstub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;

/**
 * Subscribes to the registered ntfy topic ({@code /<topic>/json} on localhost, reached through
 * {@code adb reverse}) and forwards each message body as UnifiedPush MESSAGE. Byte counts only:
 * the topic, endpoint, token and payload are not logged.
 */
public final class ForwarderService extends Service {
    private static final String CHANNEL = "upstub.forward";
    private static final int NOTIFICATION_ID = 1;
    private static final int SEEN_LIMIT = 64;

    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private volatile boolean running;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context context) {
        Intent intent = new Intent(context, ForwarderService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException ignored) {
            // A background start can be refused. The shell starts this service itself.
        }
    }

    /** ntfy {@code /json} line → raw message bytes, or null when it is not a message. */
    static byte[] messageBytes(String line) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') return null;
        try {
            JSONObject obj = new JSONObject(trimmed);
            if (!"message".equals(obj.optString("event"))) return null;
            if (!obj.has("message") || obj.isNull("message")) return null;
            String message = obj.optString("message", "");
            if (message.isEmpty()) return null;
            if ("base64".equals(obj.optString("encoding"))) return Base64.getDecoder().decode(message);
            return message.getBytes(StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    static String messageId(String line) {
        try {
            return new JSONObject(line.trim()).optString("id", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "upstub:forward");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("UP stub")
            .setContentText("Forwarding")
            .setOngoing(true)
            .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException refused) {
            // A start still attributed to BOOT_COMPLETED must not crash the process. The shell retries.
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        running = true;
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::loop, "upstub-forward");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void loop() {
        String activeTopic = null;
        int activePort = -1;
        while (running && !Thread.currentThread().isInterrupted()) {
            SharedPreferences prefs = getSharedPreferences(DistributorReceiver.PREFS, MODE_PRIVATE);
            String topic = prefs.getString(DistributorReceiver.KEY_TOPIC, null);
            String token = prefs.getString(DistributorReceiver.KEY_TOKEN, null);
            String app = prefs.getString(DistributorReceiver.KEY_PACKAGE, null);
            int port = prefs.getInt(DistributorReceiver.KEY_PORT, DistributorReceiver.DEFAULT_PORT);
            if (topic == null || topic.isEmpty() || token == null || app == null) {
                activeTopic = null;
                sleep(1000);
                continue;
            }
            String since = prefs.getString(DistributorReceiver.KEY_LAST_ID, "");
            if (since == null || since.isEmpty()) since = "all";
            if (!topic.equals(activeTopic) || port != activePort) {
                activeTopic = topic;
                activePort = port;
            }
            if (!stream(port, topic, since, token, app)) sleep(2000);
        }
    }

    /** True when the stream ended cleanly (topic changed is handled by the caller looping). */
    private boolean stream(int port, String topic, String since, String token, String app) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/" + topic + "/json?since=" + since);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) return false;
            InputStream in = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while (running && (line = reader.readLine()) != null) {
                byte[] bytes = messageBytes(line);
                if (bytes == null) continue;
                String id = messageId(line);
                if (id.isEmpty()) id = Integer.toHexString(bytes.length) + ":" + bytes[0];
                if (!remember(id) || alreadyForwarded(id)) continue;
                DistributorReceiver.deliver(this, app, token, bytes, id);
                noteForwarded(id);
            }
            return true;
        } catch (SocketTimeoutException timed) {
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean alreadyForwarded(String id) {
        String raw = getSharedPreferences(DistributorReceiver.PREFS, MODE_PRIVATE)
            .getString(DistributorReceiver.KEY_SEEN, "");
        if (raw == null || raw.isEmpty()) return false;
        for (String part : raw.split(",")) {
            if (part.equals(id)) return true;
        }
        return false;
    }

    private void noteForwarded(String id) {
        SharedPreferences prefs = getSharedPreferences(DistributorReceiver.PREFS, MODE_PRIVATE);
        String raw = prefs.getString(DistributorReceiver.KEY_SEEN, "");
        String next = (raw == null || raw.isEmpty()) ? id : raw + "," + id;
        String[] parts = next.split(",");
        if (parts.length > SEEN_LIMIT) {
            StringBuilder kept = new StringBuilder();
            for (int i = parts.length - SEEN_LIMIT; i < parts.length; i++) {
                if (kept.length() > 0) kept.append(',');
                kept.append(parts[i]);
            }
            next = kept.toString();
        }
        prefs.edit()
            .putString(DistributorReceiver.KEY_SEEN, next)
            .putString(DistributorReceiver.KEY_LAST_ID, id)
            .commit();
    }

    private boolean remember(String id) {
        synchronized (seen) {
            if (!seen.add(id)) return false;
            while (seen.size() > SEEN_LIMIT) {
                seen.remove(seen.iterator().next());
            }
            return true;
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "UP stub", NotificationManager.IMPORTANCE_MIN);
        manager.createNotificationChannel(channel);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Resumes the subscriber after reboot when a topic is already saved. specialUse is allowed here; dataSync is not. */
    public static final class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
            SharedPreferences prefs = context.getSharedPreferences(DistributorReceiver.PREFS, MODE_PRIVATE);
            String topic = prefs.getString(DistributorReceiver.KEY_TOPIC, null);
            if (topic == null || topic.isEmpty()) return;
            start(context);
        }
    }
}
