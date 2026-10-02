# up-stub

A local UnifiedPush distributor for Shroud instrumented tests (AND_3). It does not open a
network connection and it does not talk to ntfy.sh. `REGISTER` is answered with `NEW_ENDPOINT`
and the endpoint `https://up-stub.invalid/push/test`. `INJECT` forwards a payload as `MESSAGE`
to the app that registered.

This project is not included from `android/settings.gradle.kts`.

## Build and install

From `android/`, with `JAVA_HOME` pointing at JDK 21:

```
./gradlew -p e2e/up-stub assembleDebug
adb install -r e2e/up-stub/build/outputs/apk/debug/up-stub-debug.apk
```

## Inject a message

After Shroud has registered (the stub has saved the connection token and Shroud's package):

```
adb shell am broadcast -a de.corespace.shroud.upstub.INJECT \
  -n de.corespace.shroud.upstub/.DistributorReceiver \
  --es payload '<base64 of the RFC 8291 body>'
```

A raw `bytes` extra is accepted as well as `payload`.
