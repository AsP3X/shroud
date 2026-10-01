# R8 rules of the single release build (00-plan §5.2). Shared file: one owner per wave (§2.6).

# kotlinx.serialization: keep generated serializers of @Serializable classes.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes *Annotation*, InnerClasses

# BouncyCastle is used through its lightweight API only.
-dontwarn org.bouncycastle.**

# WebRTC (W3-CALLS-MEDIA): the native library calls back into Java by name (JNI).
-keep class org.webrtc.** { *; }

# whisper.cpp JNI (W2-WHISPER, media-voice-links §13.4): native methods and the segment class the
# native side constructs.
-keep class de.corespace.shroud.core.transcription.WhisperNative {
    native <methods>;
}
-keep class de.corespace.shroud.core.transcription.NativeSegment {
    <init>(...);
    <fields>;
}
