package com.tracker.youtubeshorts.network;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Resolves YouTube Short video titles and channel names using YouTube's public oEmbed API.
 * Endpoint: https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v={videoId}&format=json
 * Requires NO API key and provides 100% accurate titles.
 */
public class YouTubeMetadataHelper {

    private static final String TAG = "YouTubeMetadata";
    private static final ConcurrentHashMap<String, VideoInfo> cache = new ConcurrentHashMap<>();
    private static final OkHttpClient client = new OkHttpClient();

    public static class VideoInfo {
        public final String title;
        public final String channelName;

        public VideoInfo(String title, String channelName) {
            this.title = title;
            this.channelName = channelName;
        }
    }

    public interface MetadataCallback {
        void onResult(VideoInfo info);
    }

    public static void fetchVideoMetadata(String videoId, MetadataCallback callback) {
        if (videoId == null || videoId.isEmpty()) {
            if (callback != null) callback.onResult(new VideoInfo("YouTube Short", null));
            return;
        }

        // Check in-memory cache first
        if (cache.containsKey(videoId)) {
            if (callback != null) callback.onResult(cache.get(videoId));
            return;
        }

        String oEmbedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=" + videoId + "&format=json";

        Request request = new Request.Builder()
                .url(oEmbedUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "Failed to fetch metadata for " + videoId + ": " + e.getMessage());
                VideoInfo fallback = new VideoInfo("YouTube Short (" + videoId + ")", null);
                if (callback != null) callback.onResult(fallback);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response res = response) {
                    if (res.isSuccessful() && res.body() != null) {
                        String bodyStr = res.body().string();
                        JsonObject json = JsonParser.parseString(bodyStr).getAsJsonObject();
                        String title = json.has("title") ? json.get("title").getAsString() : "YouTube Short (" + videoId + ")";
                        String channel = json.has("author_name") ? json.get("author_name").getAsString() : null;

                        VideoInfo info = new VideoInfo(title, channel);
                        cache.put(videoId, info);
                        if (callback != null) callback.onResult(info);
                    } else {
                        VideoInfo fallback = new VideoInfo("YouTube Short (" + videoId + ")", null);
                        if (callback != null) callback.onResult(fallback);
                    }
                } catch (Exception e) {
                    VideoInfo fallback = new VideoInfo("YouTube Short (" + videoId + ")", null);
                    if (callback != null) callback.onResult(fallback);
                }
            }
        });
    }
}
