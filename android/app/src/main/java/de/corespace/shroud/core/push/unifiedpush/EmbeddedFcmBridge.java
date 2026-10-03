package de.corespace.shroud.core.push.unifiedpush;

import android.content.Context;

import org.unifiedpush.android.embedded_fcm_distributor.FailedReason;
import org.unifiedpush.android.embedded_fcm_distributor.Utils;
import org.unifiedpush.android.embedded_fcm_distributor.c2m.C2mRequests;

import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

/**
 * Play Services registration from the embedded distributor.
 *
 * Those types are internal to the library, so Kotlin in this module cannot name them.
 * The library's own receiver reads the sender only after {@code onReceive} returns, which is
 * empty on API 34+, and then it drops the registration without telling us.
 */
public final class EmbeddedFcmBridge {
    private EmbeddedFcmBridge() {}

    public static Object register(
            Context context,
            String token,
            String vapid,
            Function1<? super String, Unit> onSuccess,
            Function0<Unit> onFailure,
            Continuation<? super Unit> continuation) {
        Utils.INSTANCE.saveVapid(context, token, vapid);
        return C2mRequests.INSTANCE.c2mRegister(
                context, token, vapid, false, onSuccess, onFailure, continuation);
    }

    public static Object unregister(
            Context context, String token, Continuation<? super Unit> continuation) {
        return C2mRequests.INSTANCE.c2mUnregister(context, token, continuation);
    }

    public static void newEndpoint(Context context, String token, String fcmToken) {
        Utils.INSTANCE.sendNewEndpoint(context, token, fcmToken, false);
    }

    public static void registrationFailed(Context context, String token, String reason) {
        Utils.INSTANCE.sendRegistrationFailed(context, token, FailedReason.valueOf(reason));
    }

    public static void unregistered(Context context, String token) {
        Utils.INSTANCE.sendUnregistered(context, token);
    }
}
