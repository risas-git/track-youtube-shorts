package com.tracker.youtubeshorts.network;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tracker.youtubeshorts.model.ShortSession;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;

/**
 * Supabase PostgREST API client using OkHttp.
 * Communicates with the remote PostgreSQL instance via Supabase's auto-generated REST endpoints.
 */
public class SupabaseClient {

    private static final String TAG = "SupabaseClient";
    private static final String TABLE_NAME = "youtube_shorts_history";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private static SupabaseClient instance;
    private final OkHttpClient httpClient;
    private final Gson gson;

    public interface ApiCallback {
        void onSuccess(String responseBody);
        void onFailure(String errorMessage);
    }

    public interface FetchListCallback {
        void onSuccess(List<ShortSession> sessions);
        void onFailure(String errorMessage);
    }

    private SupabaseClient() {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build();

        this.gson = new Gson();
    }

    public static synchronized SupabaseClient getInstance() {
        if (instance == null) {
            instance = new SupabaseClient();
        }
        return instance;
    }

    /**
     * Inserts a completed YouTube Shorts session record into the Supabase database.
     * Endpoint: POST https://<project>.supabase.co/rest/v1/youtube_shorts_history
     */
    public void insertShortSession(Context context, ShortSession session, ApiCallback callback) {
        String baseUrl = SupabaseConfig.getSupabaseUrl(context);
        String apiKey = SupabaseConfig.getSupabaseKey(context);

        if (!SupabaseConfig.isConfigured(context)) {
            String error = "Supabase credentials are not configured in settings.";
            Log.w(TAG, error);
            if (callback != null) callback.onFailure(error);
            return;
        }

        // Clean up base URL if trailing slash is present
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String endpointUrl = baseUrl + "/rest/v1/" + TABLE_NAME;
        String jsonBody = gson.toJson(session);

        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

        Request request = new Request.Builder()
                .url(endpointUrl)
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .post(body)
                .build();

        Log.d(TAG, "Uploading Short Session: " + jsonBody);

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                String errorMsg = "Network error: " + e.getMessage();
                Log.e(TAG, errorMsg, e);
                session.setSynced(false);
                session.setSyncError(errorMsg);
                if (callback != null) callback.onFailure(errorMsg);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response res = response) {
                    if (res.isSuccessful()) {
                        Log.i(TAG, "Successfully synced session to Supabase! Code: " + res.code());
                        session.setSynced(true);
                        session.setSyncError(null);
                        if (callback != null) callback.onSuccess("Synced successfully (HTTP " + res.code() + ")");
                    } else {
                        String responseBody = res.body() != null ? res.body().string() : "";
                        String errorMsg = "Supabase API Error " + res.code() + ": " + responseBody;
                        Log.e(TAG, errorMsg);
                        session.setSynced(false);
                        session.setSyncError(errorMsg);
                        if (callback != null) callback.onFailure(errorMsg);
                    }
                }
            }
        });
    }

    /**
     * Fetches recent Shorts history from Supabase to display in MainActivity.
     */
    public void fetchRecentSessions(Context context, FetchListCallback callback) {
        String baseUrl = SupabaseConfig.getSupabaseUrl(context);
        String apiKey = SupabaseConfig.getSupabaseKey(context);

        if (!SupabaseConfig.isConfigured(context)) {
            if (callback != null) callback.onFailure("Credentials not configured");
            return;
        }

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String endpointUrl = baseUrl + "/rest/v1/" + TABLE_NAME + "?select=*&order=started_at.desc&limit=25";

        Request request = new Request.Builder()
                .url(endpointUrl)
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer " + apiKey)
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (callback != null) callback.onFailure(e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response res = response) {
                    if (res.isSuccessful() && res.body() != null) {
                        String json = res.body().string();
                        Type listType = new TypeToken<List<ShortSession>>(){}.getType();
                        List<ShortSession> list = gson.fromJson(json, listType);
                        if (list != null) {
                            for (ShortSession s : list) {
                                s.setSynced(true);
                            }
                        }
                        if (callback != null) callback.onSuccess(list);
                    } else {
                        String body = res.body() != null ? res.body().string() : "";
                        if (callback != null) callback.onFailure("HTTP " + res.code() + ": " + body);
                    }
                } catch (Exception e) {
                    if (callback != null) callback.onFailure("Parsing error: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Tests the connection and API key against the Supabase PostgREST endpoint.
     */
    public void testConnection(String baseUrl, String apiKey, ApiCallback callback) {
        if (baseUrl == null || baseUrl.isEmpty() || apiKey == null || apiKey.isEmpty()) {
            if (callback != null) callback.onFailure("URL and API Key cannot be empty");
            return;
        }

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String endpointUrl = baseUrl + "/rest/v1/" + TABLE_NAME + "?select=id&limit=1";

        Request request = new Request.Builder()
                .url(endpointUrl)
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer " + apiKey)
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (callback != null) callback.onFailure("Connection failed: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response res = response) {
                    if (res.isSuccessful()) {
                        if (callback != null) callback.onSuccess("Connection successful! Supabase table is accessible.");
                    } else {
                        String body = res.body() != null ? res.body().string() : "";
                        if (callback != null) callback.onFailure("HTTP " + res.code() + ": " + body);
                    }
                }
            }
        });
    }
}
