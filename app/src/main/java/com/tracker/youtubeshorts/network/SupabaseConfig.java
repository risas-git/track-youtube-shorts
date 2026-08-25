package com.tracker.youtubeshorts.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Handles persistent configuration for the Supabase REST API credentials and device identification.
 */
public class SupabaseConfig {

    private static final String PREF_NAME = "supabase_config";
    private static final String KEY_SUPABASE_URL = "supabase_url";
    private static final String KEY_SUPABASE_ANON_KEY = "supabase_anon_key";
    private static final String KEY_DEVICE_ID = "device_id";

    // Default placeholders - replace these or edit them in the app settings UI
    public static final String DEFAULT_URL = "https://bottqyeltaeewbojecnz.supabase.co";
    public static final String DEFAULT_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJvdHRxeWVsdGFlZXdib2plY256Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODc2NzY0OTEsImV4cCI6MjEwMzI1MjQ5MX0.XaCKXCrt6H_5kmwccIi-SQOWWc8OeqVcsK8GKfdDyrA";


    public static String getSupabaseUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_SUPABASE_URL, DEFAULT_URL).trim();
    }

    public static String getSupabaseKey(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_SUPABASE_ANON_KEY, DEFAULT_KEY).trim();
    }

    public static String getDeviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String defaultDevice = Build.MANUFACTURER + " " + Build.MODEL;
        return prefs.getString(KEY_DEVICE_ID, defaultDevice).trim();
    }

    public static void saveConfig(Context context, String url, String key, String deviceId) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_SUPABASE_URL, url)
                .putString(KEY_SUPABASE_ANON_KEY, key)
                .putString(KEY_DEVICE_ID, deviceId)
                .apply();
    }

    public static boolean isConfigured(Context context) {
        String url = getSupabaseUrl(context);
        String key = getSupabaseKey(context);
        return url != null && !url.isEmpty() && !url.contains("your-project-ref")
                && key != null && !key.isEmpty() && !key.contains("your-anon-key");
    }
}
