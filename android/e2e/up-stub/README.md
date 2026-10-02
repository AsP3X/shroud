# up-stub

A local UnifiedPush distributor for Shroud instrumented tests (AND_3). `REGISTER` is answered
with `NEW_ENDPOINT` and `http://localhost:<port>/up<random>?up=1` (port default 2586, or the
`de.corespace.shroud.upstub.CONFIG` extra `port`). A `specialUse` foreground service subscribes
to that topic's ntfy `/json` stream and forwards each message as `MESSAGE`. `dataSync` is not
used: a start still attributed to `BOOT_COMPLETED` is refused for that type. `INJECT` still
forwards a payload the same way. It does not talk to ntfy.sh.

This project is not included from `android/settings.gradle.kts`.

## Build and install

From `android/`, with `JAVA_HOME` pointing at JDK 21:

```
./gradlew -p e2e/up-stub assembleDebug
adb install -r e2e/up-stub/build/outputs/apk/debug/up-stub-debug.apk
```

## Point it at the local ntfy

`adb reverse tcp:<port> tcp:<port>` first (the emulator's localhost is the host's ntfy):

```
adb shell am broadcast -a de.corespace.shroud.upstub.CONFIG \
  -n de.corespace.shroud.upstub/.DistributorReceiver --ei port <port>
adb shell am start -n de.corespace.shroud.upstub/.StarterActivity --ei port <port>
```

## Inject a message

After Shroud has registered (the stub has saved the connection token and Shroud's package):

```
adb shell am broadcast -a de.corespace.shroud.upstub.INJECT \
  -n de.corespace.shroud.upstub/.DistributorReceiver \
  --es payload '<base64 of the RFC 8291 body>'
```

A raw `bytes` extra is accepted as well as `payload`.
