# QuickInk release ProGuard / R8 rules.
#
# Most modern AndroidX + Google libraries ship their own consumer
# rules in their AARs (Compose, Room, kotlinx.serialization 1.5+,
# Coroutines, OkHttp, Coil, WorkManager, Credential Manager, etc.),
# so this file only adds rules that are SPECIFIC to QuickInk's
# code paths or to a small set of stragglers without baked-in
# rules.

# Keep stack traces useful when symbolicating crashes from Play
# Console / Crashlytics. R8's default android-optimize ProGuard
# config already strips most other attributes.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Anything explicitly marked @Keep should not be touched.
-keep,allowobfuscation @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class *
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <init>(...);
}

# kotlinx.serialization — the plugin emits keep rules into the
# AAR consumer-rules path for most cases, but the rules can miss
# nested @Serializable types referenced only through reflection
# (KSerializer<T>::class, Json.encodeToString(value), etc.). The
# block below is the project-wide safety net used by the
# kotlinx-serialization sample. Scoped to QuickInk + shared
# packages so we don't bloat the keep set.
-keepclasseswithmembers class app.quickink.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class app.quickink.mobile.** {
    *** Companion;
}
-keep class app.quickink.mobile.**$$serializer { *; }

-keepclasseswithmembers class app.releaf.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class app.releaf.** {
    *** Companion;
}
-keep class app.releaf.**$$serializer { *; }

# Room — schema validation classes and generated DAOs are kept
# by Room's consumer rules. Belt-and-braces: keep our @Entity /
# @Dao / @Database classes by annotation so any reflection from
# the generated code doesn't get obfuscated out from under it.
-keep,allowobfuscation,allowshrinking class * {
    @androidx.room.Entity <fields>;
    @androidx.room.PrimaryKey <fields>;
    @androidx.room.ColumnInfo <fields>;
}
-keep @androidx.room.Database class *
-keep @androidx.room.Dao interface *
-keep @androidx.room.Entity class *

# Coroutines internal — preserves the slow-path reflection used
# by `kotlinx.coroutines.debug`. Kotlin stdlib already keeps
# `kotlin.coroutines.Continuation`, so this is a small extra.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ML Kit document scanner — uses reflection internally to
# resolve the dynamic-feature module. The play-services-mlkit
# AAR ships consumer rules; explicit keep here in case the
# transitive rule set drifts.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# Compose Navigation — argument types parcelled via reflection.
# Library consumer rules handle the common cases; keep
# QuickInk's typed args explicitly if any are added later.

# kotlinx.serialization runtime polymorphism — needed if we ever
# use sealed-class @Serializable hierarchies. Cheap to leave on.
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}

# Suppress warnings for optional/transitive deps that R8 doesn't
# need to resolve. Each suppression is intentional; review
# before adding more.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
