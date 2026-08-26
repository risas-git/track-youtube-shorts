package com.tracker.youtubeshorts.model;

import com.google.gson.annotations.SerializedName;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Represents a single YouTube Short viewing session record to be stored in Supabase PostgreSQL.
 */
public class ShortSession {

    @SerializedName("video_id")
    private final String videoId;

    @SerializedName("title")
    private String title;

    @SerializedName("channel_name")
    private String channelName;

    @SerializedName("url")
    private final String url;

    @SerializedName("duration_seconds")
    private final int durationSeconds;

    @SerializedName("started_at")
    private final String startedAt;

    @SerializedName("ended_at")
    private final String endedAt;

    @SerializedName("device_id")
    private final String deviceId;

    // Non-serialized sync status for UI presentation
    private transient boolean isSynced;
    private transient String syncError;

    public ShortSession(String videoId, String title, String channelName, String url, int durationSeconds, long startTimeMillis, long endTimeMillis, String deviceId) {
        this.videoId = videoId;
        this.title = (title != null && !title.isEmpty()) ? title : "YouTube Short (" + videoId + ")";
        this.channelName = channelName;
        this.url = url;
        this.durationSeconds = durationSeconds;
        this.startedAt = formatIso8601(startTimeMillis);
        this.endedAt = formatIso8601(endTimeMillis);
        this.deviceId = deviceId;
        this.isSynced = false;
    }

    public static String formatIso8601(long timeMillis) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(timeMillis));
    }

    public String getVideoId() {
        return videoId;
    }

    public String getTitle() {
        return title != null ? title : "YouTube Short (" + videoId + ")";
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public String getUrl() {
        return url;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public String getEndedAt() {
        return endedAt;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public boolean isSynced() {
        return isSynced;
    }

    public void setSynced(boolean synced) {
        isSynced = synced;
    }

    public String getSyncError() {
        return syncError;
    }

    public void setSyncError(String syncError) {
        this.syncError = syncError;
    }
}
