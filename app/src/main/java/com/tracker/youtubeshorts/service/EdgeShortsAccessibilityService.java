package com.tracker.youtubeshorts.service;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.tracker.youtubeshorts.model.ShortSession;
import com.tracker.youtubeshorts.network.SupabaseClient;
import com.tracker.youtubeshorts.network.SupabaseConfig;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AccessibilityService that monitors Microsoft Edge mobile browser for YouTube Shorts URLs,
 * measures active watch duration per Short, and dispatches session events to Supabase.
 */
public class EdgeShortsAccessibilityService extends AccessibilityService {

    private static final String TAG = "EdgeShortsService";

    // Supported Edge package names
    private static final Set<String> EDGE_PACKAGES = new HashSet<>(Arrays.asList(
            "com.microsoft.emmx",
            "com.microsoft.emmx.canary",
            "com.microsoft.emmx.dev",
            "com.microsoft.emmx.beta"
    ));

    // Common Edge/Chromium URL bar view resource IDs
    private static final String[] URL_BAR_VIEW_IDS = new String[]{
            "com.microsoft.emmx:id/url_bar",
            "com.microsoft.emmx:id/search_box_text",
            "com.microsoft.emmx:id/omnibox_title_text",
            "com.microsoft.emmx:id/line_1"
    };

    // Regex pattern to extract YouTube Shorts video ID
    // Matches https://youtube.com/shorts/{id}, https://m.youtube.com/shorts/{id}, youtube.com/shorts/{id}, etc.
    private static final Pattern SHORTS_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.|m\\.)?youtube\\.com/shorts/([a-zA-Z0-9_-]{11}|[a-zA-Z0-9_-]+)",
            Pattern.CASE_INSENSITIVE
    );

    // Minimum watch duration in seconds to consider it a valid watch (filters accidental swipe-throughs)
    private static final int MIN_WATCH_DURATION_SECONDS = 2;

    // Broadcast action for communicating tracked sessions to MainActivity
    public static final String ACTION_SESSION_LOGGED = "com.tracker.youtubeshorts.ACTION_SESSION_LOGGED";
    public static final String EXTRA_VIDEO_ID = "extra_video_id";
    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_DURATION = "extra_duration";
    public static final String EXTRA_STARTED_AT = "extra_started_at";
    public static final String EXTRA_ENDED_AT = "extra_ended_at";
    public static final String EXTRA_SYNCED = "extra_synced";
    public static final String EXTRA_ERROR = "extra_error";

    // Active session state
    private String currentVideoId = null;
    private String currentFullUrl = null;
    private long sessionStartTimeMillis = 0L;

    // Debouncing handler to avoid heavy tree traversal on every rapid event
    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingInspectionTask = null;

    // Screen off receiver to finalize session when phone is locked
    private BroadcastReceiver screenOffReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "EdgeShortsAccessibilityService created.");
        registerScreenReceiver();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "EdgeShortsAccessibilityService connected and ready to monitor Edge.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence pkg = event.getPackageName();
        String packageName = pkg != null ? pkg.toString() : "";

        // If user switched away from Edge to another app or home screen, finalize active Short
        if (!EDGE_PACKAGES.contains(packageName)) {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                finalizeCurrentSession("User left Microsoft Edge (switched to " + packageName + ")");
            }
            return;
        }

        // Debounce event processing to 300ms to preserve battery and CPU
        if (pendingInspectionTask != null) {
            debounceHandler.removeCallbacks(pendingInspectionTask);
        }

        pendingInspectionTask = () -> inspectActiveWindow();
        debounceHandler.postDelayed(pendingInspectionTask, 300);
    }

    /**
     * Inspects the active window hierarchy to extract the current URL from Edge.
     */
    private void inspectActiveWindow() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        try {
            String detectedUrl = extractUrlFromNodeTree(rootNode);

            if (detectedUrl != null && !detectedUrl.isEmpty()) {
                Matcher matcher = SHORTS_PATTERN.matcher(detectedUrl);
                if (matcher.find()) {
                    String videoId = matcher.group(1);
                    handleShortDetected(videoId, detectedUrl);
                    return;
                }
            }

            // If we detected a valid URL that is NOT a YouTube Short, finalize active short session
            if (detectedUrl != null && !detectedUrl.isEmpty() && currentVideoId != null) {
                finalizeCurrentSession("Navigated away from YouTube Shorts to: " + detectedUrl);
            }

        } finally {
            rootNode.recycle();
        }
    }

    /**
     * Extracts URL text from Edge by trying known resource IDs first, then falling back to recursive scan.
     */
    private String extractUrlFromNodeTree(AccessibilityNodeInfo rootNode) {
        // Strategy 1: Search by known resource IDs
        for (String viewId : URL_BAR_VIEW_IDS) {
            List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    CharSequence text = node.getText();
                    if (text != null && text.length() > 0) {
                        String url = text.toString().trim();
                        recycleNodeList(nodes);
                        return url;
                    }
                }
                recycleNodeList(nodes);
            }
        }

        // Strategy 2: Recursive Depth-First Search for text matching youtube.com/shorts/
        return findShortsUrlRecursively(rootNode, 0, 15);
    }

    /**
     * Recursive helper to search node tree for Shorts URL text.
     */
    private String findShortsUrlRecursively(AccessibilityNodeInfo node, int depth, int maxDepth) {
        if (node == null || depth > maxDepth) return null;

        CharSequence text = node.getText();
        if (text != null) {
            String textStr = text.toString();
            if (SHORTS_PATTERN.matcher(textStr).find()) {
                return textStr;
            }
        }

        CharSequence contentDesc = node.getContentDescription();
        if (contentDesc != null) {
            String descStr = contentDesc.toString();
            if (SHORTS_PATTERN.matcher(descStr).find()) {
                return descStr;
            }
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                String found = findShortsUrlRecursively(child, depth + 1, maxDepth);
                child.recycle();
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    /**
     * Handles state transitions when a YouTube Short is detected.
     */
    private void handleShortDetected(String videoId, String rawUrl) {
        long now = System.currentTimeMillis();

        // If same Short is still playing, keep timer running
        if (videoId.equals(currentVideoId)) {
            return;
        }

        // New short detected! First, finalize previous session if one was active
        if (currentVideoId != null) {
            finalizeCurrentSession("Swiped/navigated to new Short: " + videoId);
        }

        // Start tracking new Short
        currentVideoId = videoId;
        currentFullUrl = normalizeShortsUrl(videoId, rawUrl);
        sessionStartTimeMillis = now;

        Log.i(TAG, "Started tracking YouTube Short: " + videoId + " (" + currentFullUrl + ")");
    }

    /**
     * Finalizes the current Short session, calculates watch duration, and uploads to Supabase.
     */
    private synchronized void finalizeCurrentSession(String reason) {
        if (currentVideoId == null || sessionStartTimeMillis == 0) {
            return;
        }

        long endTimeMillis = System.currentTimeMillis();
        long durationMillis = endTimeMillis - sessionStartTimeMillis;
        int durationSeconds = (int) (durationMillis / 1000);

        String finishedVideoId = currentVideoId;
        String finishedUrl = currentFullUrl;
        long startTimeMillis = sessionStartTimeMillis;

        // Reset state immediately to prevent duplicate finalization
        currentVideoId = null;
        currentFullUrl = null;
        sessionStartTimeMillis = 0L;

        Log.d(TAG, "Finalizing session for " + finishedVideoId + " (" + durationSeconds + "s). Reason: " + reason);

        // Filter out accidental swipes or instant skips (< 2s)
        if (durationSeconds < MIN_WATCH_DURATION_SECONDS) {
            Log.d(TAG, "Ignoring Short session < " + MIN_WATCH_DURATION_SECONDS + "s (" + durationSeconds + "s)");
            return;
        }

        String deviceId = SupabaseConfig.getDeviceId(this);
        ShortSession session = new ShortSession(
                finishedVideoId,
                finishedUrl,
                durationSeconds,
                startTimeMillis,
                endTimeMillis,
                deviceId
        );

        // Dispatch to Supabase via OkHttp PostgREST API
        SupabaseClient.getInstance().insertShortSession(this, session, new SupabaseClient.ApiCallback() {
            @Override
            public void onSuccess(String responseBody) {
                broadcastSessionLogged(session, true, null);
            }

            @Override
            public void onFailure(String errorMessage) {
                broadcastSessionLogged(session, false, errorMessage);
            }
        });

        // Broadcast immediate local update
        broadcastSessionLogged(session, false, "Syncing to Supabase...");
    }

    /**
     * Sends local broadcast to notify MainActivity UI of the newly logged session.
     */
    private void broadcastSessionLogged(ShortSession session, boolean synced, String errorMsg) {
        Intent intent = new Intent(ACTION_SESSION_LOGGED);
        intent.putExtra(EXTRA_VIDEO_ID, session.getVideoId());
        intent.putExtra(EXTRA_URL, session.getUrl());
        intent.putExtra(EXTRA_DURATION, session.getDurationSeconds());
        intent.putExtra(EXTRA_STARTED_AT, session.getStartedAt());
        intent.putExtra(EXTRA_ENDED_AT, session.getEndedAt());
        intent.putExtra(EXTRA_SYNCED, synced);
        intent.putExtra(EXTRA_ERROR, errorMsg);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private String normalizeShortsUrl(String videoId, String rawUrl) {
        if (rawUrl != null && rawUrl.startsWith("http")) {
            return rawUrl;
        }
        return "https://www.youtube.com/shorts/" + videoId;
    }

    private void recycleNodeList(List<AccessibilityNodeInfo> nodes) {
        if (nodes != null) {
            for (AccessibilityNodeInfo node : nodes) {
                if (node != null) {
                    node.recycle();
                }
            }
        }
    }

    private void registerScreenReceiver() {
        screenOffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    Log.d(TAG, "Screen turned off. Finalizing active Short session.");
                    finalizeCurrentSession("Device screen turned off");
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenOffReceiver, filter);
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted.");
        finalizeCurrentSession("Accessibility service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "EdgeShortsAccessibilityService destroyed.");
        finalizeCurrentSession("Accessibility service destroyed");

        if (screenOffReceiver != null) {
            try {
                unregisterReceiver(screenOffReceiver);
            } catch (Exception ignored) {}
        }
        debounceHandler.removeCallbacksAndMessages(null);
    }
}
