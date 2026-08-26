package com.tracker.youtubeshorts.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

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
 * Enhanced AccessibilityService that monitors Microsoft Edge (and Chromium browsers)
 * for YouTube Shorts, tracks active watch time, and sends data to Supabase.
 */
public class EdgeShortsAccessibilityService extends AccessibilityService {

    private static final String TAG = "EdgeShortsService";

    // Supported browser package names
    private static final Set<String> TARGET_BROWSER_PACKAGES = new HashSet<>(Arrays.asList(
            "com.microsoft.emmx",
            "com.microsoft.emmx.canary",
            "com.microsoft.emmx.dev",
            "com.microsoft.emmx.beta",
            "com.android.chrome",
            "com.google.android.youtube"
    ));

    // Common Edge/Chromium URL bar view resource IDs
    private static final String[] URL_BAR_VIEW_IDS = new String[]{
            "com.microsoft.emmx:id/url_bar",
            "com.microsoft.emmx:id/search_box_text",
            "com.microsoft.emmx:id/omnibox_title_text",
            "com.microsoft.emmx:id/line_1",
            "com.microsoft.emmx:id/toolbar",
            "com.android.chrome:id/url_bar",
            "org.chromium.chrome:id/url_bar"
    };

    // Regex patterns to detect YouTube Shorts video IDs
    private static final Pattern[] SHORTS_PATTERNS = new Pattern[]{
            // Standard: youtube.com/shorts/{id} or m.youtube.com/shorts/{id}
            Pattern.compile("(?:https?://)?(?:www\\.|m\\.)?youtube\\.com/shorts/([a-zA-Z0-9_-]{11}|[a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE),
            // Shortened URL bar text: shorts/{id}
            Pattern.compile("(?:^|/|\\s)shorts/([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE),
            // Short link: youtu.be/{id}
            Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE)
    };

    // Minimum watch duration in seconds
    private static final int MIN_WATCH_DURATION_SECONDS = 2;

    // Broadcast actions
    public static final String ACTION_SESSION_LOGGED = "com.tracker.youtubeshorts.ACTION_SESSION_LOGGED";
    public static final String ACTION_DIAGNOSTIC_UPDATE = "com.tracker.youtubeshorts.ACTION_DIAGNOSTIC_UPDATE";

    public static final String EXTRA_VIDEO_ID = "extra_video_id";
    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_DURATION = "extra_duration";
    public static final String EXTRA_STARTED_AT = "extra_started_at";
    public static final String EXTRA_ENDED_AT = "extra_ended_at";
    public static final String EXTRA_SYNCED = "extra_synced";
    public static final String EXTRA_ERROR = "extra_error";

    public static final String EXTRA_DIAG_PACKAGE = "extra_diag_package";
    public static final String EXTRA_DIAG_URL = "extra_diag_url";
    public static final String EXTRA_DIAG_ACTIVE_ID = "extra_diag_active_id";
    public static final String EXTRA_DIAG_ACTIVE_SEC = "extra_diag_active_sec";
    public static final String EXTRA_DIAG_EVENT_COUNT = "extra_diag_event_count";

    // Active session state
    private String currentVideoId = null;
    private String currentFullUrl = null;
    private long sessionStartTimeMillis = 0L;
    private long totalEventsReceived = 0;
    private String lastSeenPackage = "None";
    private String lastScannedUrl = "None";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingInspectionTask = null;
    private Runnable liveTickerRunnable = null;

    private BroadcastReceiver screenOffReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "EdgeShortsAccessibilityService created.");
        registerScreenReceiver();
        startLiveTicker();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "EdgeShortsAccessibilityService connected!");

        // Dynamically request enhanced web accessibility flags
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                        | AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;
                setServiceInfo(info);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not set enhanced flags dynamically: " + e.getMessage());
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        totalEventsReceived++;
        CharSequence pkg = event.getPackageName();
        if (pkg != null) {
            lastSeenPackage = pkg.toString();
        }

        // If user left browser completely, finalize active Short
        if (!TARGET_BROWSER_PACKAGES.contains(lastSeenPackage)) {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && currentVideoId != null) {
                finalizeCurrentSession("Switched away from browser to: " + lastSeenPackage);
            }
            return;
        }

        // Check text/contentDescription directly on event
        checkEventTextForShorts(event);

        // Debounce active window inspection (250ms)
        if (pendingInspectionTask != null) {
            mainHandler.removeCallbacks(pendingInspectionTask);
        }

        pendingInspectionTask = this::inspectWindowsForShorts;
        mainHandler.postDelayed(pendingInspectionTask, 250);
    }

    private void checkEventTextForShorts(AccessibilityEvent event) {
        if (event.getText() != null) {
            for (CharSequence seq : event.getText()) {
                if (seq != null) {
                    String text = seq.toString();
                    String matchedId = extractVideoId(text);
                    if (matchedId != null) {
                        lastScannedUrl = text;
                        handleShortDetected(matchedId, text);
                        return;
                    }
                }
            }
        }

        CharSequence desc = event.getContentDescription();
        if (desc != null) {
            String descStr = desc.toString();
            String matchedId = extractVideoId(descStr);
            if (matchedId != null) {
                lastScannedUrl = descStr;
                handleShortDetected(matchedId, descStr);
            }
        }
    }

    /**
     * Inspects active window and all interactive windows.
     */
    private void inspectWindowsForShorts() {
        boolean foundInActive = false;

        // Try primary root in active window
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            try {
                String detected = extractShortsFromNodeTree(rootNode);
                if (detected != null) {
                    foundInActive = true;
                }
            } finally {
                rootNode.recycle();
            }
        }

        // If not found in primary window, inspect all interactive windows
        if (!foundInActive && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo window : windows) {
                        AccessibilityNodeInfo windowRoot = window.getRoot();
                        if (windowRoot != null) {
                            try {
                                String detected = extractShortsFromNodeTree(windowRoot);
                                if (detected != null) {
                                    break;
                                }
                            } finally {
                                windowRoot.recycle();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "Window inspection exception: " + e.getMessage());
            }
        }
    }

    /**
     * Searches a node tree using known URL bar IDs and recursive DFS.
     */
    private String extractShortsFromNodeTree(AccessibilityNodeInfo root) {
        if (root == null) return null;

        // Strategy 1: Known URL bar IDs
        for (String id : URL_BAR_VIEW_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    CharSequence text = node.getText();
                    if (text != null && text.length() > 0) {
                        String str = text.toString().trim();
                        lastScannedUrl = str;
                        String videoId = extractVideoId(str);
                        if (videoId != null) {
                            recycleNodeList(nodes);
                            handleShortDetected(videoId, str);
                            return str;
                        }
                    }
                }
                recycleNodeList(nodes);
            }
        }

        // Strategy 2: Recursive DFS
        return searchShortsRecursively(root, 0, 20);
    }

    private String searchShortsRecursively(AccessibilityNodeInfo node, int depth, int maxDepth) {
        if (node == null || depth > maxDepth) return null;

        CharSequence text = node.getText();
        if (text != null) {
            String str = text.toString();
            String videoId = extractVideoId(str);
            if (videoId != null) {
                lastScannedUrl = str;
                handleShortDetected(videoId, str);
                return str;
            }
        }

        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String str = desc.toString();
            String videoId = extractVideoId(str);
            if (videoId != null) {
                lastScannedUrl = str;
                handleShortDetected(videoId, str);
                return str;
            }
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                String found = searchShortsRecursively(child, depth + 1, maxDepth);
                child.recycle();
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    /**
     * Matches raw text against all Shorts patterns to extract Video ID.
     */
    private String extractVideoId(String text) {
        if (text == null || text.isEmpty()) return null;

        for (Pattern pattern : SHORTS_PATTERNS) {
            Matcher m = pattern.matcher(text);
            if (m.find()) {
                String id = m.group(1);
                if (id != null && !id.equalsIgnoreCase("shorts") && id.length() >= 4) {
                    return id;
                }
            }
        }
        return null;
    }

    private void handleShortDetected(String videoId, String rawUrl) {
        long now = System.currentTimeMillis();

        if (videoId.equals(currentVideoId)) {
            return;
        }

        // Finalize previous Short if active
        if (currentVideoId != null) {
            finalizeCurrentSession("Swiped to next Short: " + videoId);
        }

        currentVideoId = videoId;
        currentFullUrl = normalizeShortsUrl(videoId, rawUrl);
        sessionStartTimeMillis = now;

        Log.i(TAG, ">>> TRACKING SHORT: " + videoId + " (" + currentFullUrl + ")");
        broadcastDiagnosticUpdate();
    }

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

        currentVideoId = null;
        currentFullUrl = null;
        sessionStartTimeMillis = 0L;

        Log.i(TAG, "Finalizing Short: " + finishedVideoId + " (" + durationSeconds + "s). Reason: " + reason);

        if (durationSeconds < MIN_WATCH_DURATION_SECONDS) {
            Log.d(TAG, "Skipped Short under " + MIN_WATCH_DURATION_SECONDS + "s");
            broadcastDiagnosticUpdate();
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

        // Upload to Supabase
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

        // Broadcast local update
        broadcastSessionLogged(session, false, "Uploading to Supabase...");
        broadcastDiagnosticUpdate();
    }

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

    private void broadcastDiagnosticUpdate() {
        Intent intent = new Intent(ACTION_DIAGNOSTIC_UPDATE);
        intent.putExtra(EXTRA_DIAG_PACKAGE, lastSeenPackage);
        intent.putExtra(EXTRA_DIAG_URL, lastScannedUrl);
        intent.putExtra(EXTRA_DIAG_ACTIVE_ID, currentVideoId != null ? currentVideoId : "None (Idle)");
        int activeSec = currentVideoId != null && sessionStartTimeMillis > 0
                ? (int) ((System.currentTimeMillis() - sessionStartTimeMillis) / 1000)
                : 0;
        intent.putExtra(EXTRA_DIAG_ACTIVE_SEC, activeSec);
        intent.putExtra(EXTRA_DIAG_EVENT_COUNT, totalEventsReceived);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void startLiveTicker() {
        liveTickerRunnable = new Runnable() {
            @Override
            public void run() {
                broadcastDiagnosticUpdate();
                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.postDelayed(liveTickerRunnable, 1000);
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
                    finalizeCurrentSession("Device screen turned off");
                }
            }
        };
        registerReceiver(screenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    @Override
    public void onInterrupt() {
        finalizeCurrentSession("Accessibility Service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        finalizeCurrentSession("Accessibility Service destroyed");

        if (screenOffReceiver != null) {
            try {
                unregisterReceiver(screenOffReceiver);
            } catch (Exception ignored) {}
        }
        if (liveTickerRunnable != null) {
            mainHandler.removeCallbacks(liveTickerRunnable);
        }
        mainHandler.removeCallbacksAndMessages(null);
    }
}
