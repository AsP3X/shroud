# kotlinx.serialization: keep generated serializers of @Serializable classes.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes *Annotation*, InnerClasses
# BouncyCastle is used through its lightweight API only.
-dontwarn org.bouncycastle.**
