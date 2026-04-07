# Kodi — ProGuard rules

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Gson — keep data model classes used with serialization
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class ai.kodi.app.data.** { *; }

# Porcupine native SDK
-keep class ai.picovoice.** { *; }
-dontwarn ai.picovoice.**

# Kodi accessibility & voice service components
-keep class ai.kodi.app.accessibility.** { *; }
-keep class ai.kodi.app.voice.** { *; }

# Android standard
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
