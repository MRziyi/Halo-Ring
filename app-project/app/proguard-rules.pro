# ============================================================================
# Halo Ring · 环意 — R8 / ProGuard keep rules for release builds
#
# Philosophy: be as aggressive as possible with shrinking, only keep what
# survives runtime reflection or implicit class lookup. Everything else lets
# R8 strip class-by-class. Target release APK size: ~7–8 MB (debug is ~14 MB,
# of which ~4 MB is BouncyCastle).
# ============================================================================


# ── Kotlin metadata + reflection ──────────────────────────────────────────────
# Without these, kotlin.coroutines, kotlin reflect-style features can crash at
# runtime with NoClassDefFoundError on inner classes / generic signatures.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*
-keepattributes RuntimeVisible*Annotations, AnnotationDefault


# ── Coroutines ────────────────────────────────────────────────────────────────
# kotlinx-coroutines uses reflection internally for service-loader lookups
# (Dispatchers.Main, exception handlers). The bundled rules cover most of it,
# but we explicitly keep the SafeContinuation / ContinuationImpl machinery so
# stack traces in release are debuggable.
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}
-keep class kotlinx.coroutines.flow.** { *; }


# ── BouncyCastle (the heavyweight dependency, ~4 MB before shrink) ────────────
# BC uses reflective service-loader (META-INF/services) for its providers.
# Without these keeps, X.509 cert generation in AdbCrypto crashes at runtime.
# We allow R8 to shrink the BC tree where it can, but keep the surfaces we
# actually call into.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.x509.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.operator.** { *; }
-keep class org.bouncycastle.pkcs.** { *; }
-keep class org.bouncycastle.openssl.** { *; }
-keep class org.bouncycastle.util.** { *; }
# BC's internal IPv6 / asn1 codec uses reflection on these classes
-keepclassmembers class org.bouncycastle.** {
    static final *** *;
    private static *** *;
}
# Silence R8 warnings on BC's optional JDK-21 features
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**


# ── DataStore preferences ─────────────────────────────────────────────────────
# Preferences DataStore uses byte-buffer reflection on the Preferences type;
# default rules suffice but defensive keep here in case AGP version drifts.
-keep class androidx.datastore.preferences.protobuf.** { *; }
-dontwarn androidx.datastore.preferences.protobuf.**


# ── Compose runtime ───────────────────────────────────────────────────────────
# Mostly handled by Compose's own consumer-proguard rules; explicit keeps below
# are for the Snapshot system which uses reflection on @Stable types.
-keep class androidx.compose.runtime.Composer { *; }
-keep class androidx.compose.runtime.snapshots.** { *; }
-keepclasseswithmembers class androidx.compose.** {
    @androidx.compose.runtime.Stable <methods>;
    @androidx.compose.runtime.Immutable <methods>;
}


# ── Our app: manifest-referenced classes (R8 already keeps these via AGP) ────
# Defensive declarations in case the manifest-class graph gets confused.
-keep public class com.halo.ring.HaloRingApplication
-keep public class com.halo.ring.MainActivity
-keep public class com.halo.ring.service.HaloRingService
-keep public class com.halo.ring.accessibility.HaloRingAccessibilityService
-keep public class com.halo.ring.receiver.BootReceiver


# ── BLE protocol constants (R8 might inline + strip; ensure runtime hex matches) ─
# R08Protocol.SERVICE_UUID etc. are read via reflection in some debug HUD paths.
-keepclassmembers class com.halo.ring.core.ble.R08Protocol {
    public static final *** *;
}


# ── DeviceProfile enum (used by runtime detection + serialized in DataStore) ──
-keep public enum com.halo.ring.core.** { *; }
-keep public enum com.halo.ring.** { *; }


# ── BuildConfig (versionName etc., read by About screen) ──────────────────────
-keep class com.halo.ring.BuildConfig { *; }


# ── Suppress noisy warnings ──────────────────────────────────────────────────
# These are optional dependencies we don't pull in but that BC / Kotlin
# reference in their manifests.
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
-dontwarn sun.misc.**


# ── Source-level debug info (release builds — minimal, for crash dedup) ──────
# Keep file names + line numbers so play-console-style symbolicated stacks
# remain useful. Strip everything else.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile, LineNumberTable
