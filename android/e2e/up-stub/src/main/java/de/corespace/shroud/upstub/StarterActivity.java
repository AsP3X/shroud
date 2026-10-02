package de.corespace.shroud.upstub;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * Starts {@link ForwarderService} while this process is in the foreground.
 * A broadcast receiver on API 31+ cannot call {@code startForegroundService}.
 */
public final class StarterActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int port = getIntent() == null ? -1 : getIntent().getIntExtra("port", -1);
        if (port > 0 && port < 65536) {
            SharedPreferences prefs = getSharedPreferences(DistributorReceiver.PREFS, MODE_PRIVATE);
            prefs.edit().putInt(DistributorReceiver.KEY_PORT, port).commit();
        }
        ForwarderService.start(this);
        finish();
    }
}
