# Releaf ProGuard rules.
# Default release build has minify disabled; these rules kick in once enabled.

# Keep kotlinx.serialization model classes (data/**/* + @Serializable)
-keep,includedescriptorclasses class app.releaf.mobile.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class ** { kotlinx.serialization.KSerializer *; }
