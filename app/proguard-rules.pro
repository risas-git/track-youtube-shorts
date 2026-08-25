# Keep Supabase model fields for Gson serialization
-keepclassmembers class com.tracker.youtubeshorts.model.ShortSession {
    <fields>;
}

# Keep OkHttp & Gson
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.google.gson.** { *; }
