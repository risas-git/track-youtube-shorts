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
import com.tracker.youtubeshorts.network.YouTubeMetadataHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Universal AccessibilityService that monitors Microsoft Edge, Samsung Internet, Chrome,
 * and YouTube for Shorts, logs every Short session, and streams data to Supabase.
 */
public class EdgeShortsAccessibilityService extends AccessibilityService {

    private static final String TAG = "EdgeShortsService";

    // Supported browser and video packages
    private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
            "com.microsoft.emmx",
            "com.microsoft.emmx.canary",
            "com.microsoft.emmx.dev",
            "com.microsoft.emmx.beta",
            "com.microsoft.bing",
            "com.microsoft.copilot",
            "com.android.chrome",
            "com.sec.android.app.sbrowser",
            "com.google.android.youtube"
    ));

    private static final String[] URL_BAR_VIEW_IDS = new String[]{
            "com.microsoft.emmx:id/url_bar",
            "com.microsoft.emmx:id/search_box_text",
            "com.microsoft.emmx:id/omnibox_title_text",
            "com.microsoft.emmx:id/line_1",
            "com.microsoft.emmx:id/toolbar",
            "com.android.chrome:id/url_bar",
            "org.chromium.chrome:id/url_bar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text"
    };

    private static final Pattern[] VIDEO_ID_PATTERNS = new Pattern[]{
            Pattern.compile("(?:https?://)?(?:www\\.|m\\.)?youtube\\.com/shorts/([a-zA-Z0-9_-]{11}|[a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:^|/|\\s)shorts/([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})", Pattern.CASE_INSENSITIVE)
    };

    private static final int MIN_WATCH_DURATION_SECONDS = 2;

    public static final String ACTION_SESSION_LOGGED = "com.tracker.youtubeshorts.ACTION_SESSION_LOGGED";
    public static final String ACTION_DIAGNOSTIC_UPDATE = "com.tracker.youtubeshorts.ACTION_DIAGNOSTIC_UPDATE";

    public static final String EXTRA_VIDEO_ID = "extra_video_id";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_CHANNEL = "extra_channel";
    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_DURATION = "extra_duration";
    public static final String EXTRA_STARTED_AT = "extra_started_at";
    public static final String EXTRA_ENDED_AT = "extra_ended_at";
    public static final String EXTRA_SYNCED = "extra_synced";
    public static final String EXTRA_ERROR = "extra_error";

    public static final String EXTRA_DIAG_PACKAGE = "extra_diag_package";
    public static final String EXTRA_DIAG_URL = "extra_diag_url";
    public static final String EXTRA_DIAG_ACTIVE_ID = "extra_diag_active_id";
    public static final String EXTRA_DIAG_ACTIVE_TITLE = "extra_diag_active_title";
    public static final String EXTRA_DIAG_ACTIVE_SEC = "extra_diag_active_sec";
    public static final String EXTRA_DIAG_EVENT_COUNT = "extra_diag_event_count";

    // Active session tracking state
    private String currentVideoId = null;
    private String currentTitle = null;
    private String currentChannelName = null;
    private String currentFullUrl = null;
    private long sessionStartTimeMillis = 0L;

    private long totalEventsReceived = 0;
    private String lastSeenPackage = "None";
    private String lastScannedUrl = "None";
    private boolean isInsideYouTubeShortsTab = false;

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

        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
                info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                        | AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;
                setServiceInfo(info);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not set enhanced flags: " + e.getMessage());
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

        boolean isTarget = isTargetPackage(lastSeenPackage);

        // If user left browser/YouTube app, finalize active Short
        if (!isTarget) {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && currentVideoId != null) {
                finalizeCurrentSession("Switched away from browser to: " + lastSeenPackage);
            }
            return;
        }

        // Check text / contentDescription directly on event
        checkEventTextForShorts(event);

        // Debounce window inspection (200ms)
        if (pendingInspectionTask != null) {
            mainHandler.removeCallbacks(pendingInspectionTask);
        }

        pendingInspectionTask = this::inspectWindowsForShorts;
        mainHandler.postDelayed(pendingInspectionTask, 200);
    }

    private boolean isTargetPackage(String pkg) {
        if (pkg == null) return false;
        String lower = pkg.toLowerCase();
        if (TARGET_PACKAGES.contains(pkg)) return true;
        return lower.contains("emmx") || lower.contains("chrome") || lower.contains("browser") || lower.contains("youtube");
    }

    private void checkEventTextForShorts(AccessibilityEvent event) {
        if (event.getText() != null) {
            for (CharSequence seq : event.getText()) {
                if (seq != null) {
                    String text = seq.toString();
                    String matchedId = extractVideoId(text);
                    if (matchedId != null) {
                        lastScannedUrl = text;
                        handleShortDetected(matchedId, null, null, text);
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
                handleShortDetected(matchedId, null, null, descStr);
            }
        }
    }

    private void inspectWindowsForShorts() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode != null) {
            try {
                inspectNodeTree(rootNode);
            } finally {
                rootNode.recycle();
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<AccessibilityWindowInfo> windows = getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo window : windows) {
                        AccessibilityNodeInfo windowRoot = window.getRoot();
                        if (windowRoot != null) {
                            try {
                                inspectNodeTree(windowRoot);
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

    private void inspectNodeTree(AccessibilityNodeInfo root) {
        if (root == null) return;

        String urlBarVideoId = null;
        String urlBarRawText = null;

        // 1. Check URL bar state
        for (String id : URL_BAR_VIEW_IDS) {
            List<AccessibilityNodeInfo> urlNodes = root.findAccessibilityNodeInfosByViewId(id);
            if (urlNodes != null && !urlNodes.isEmpty()) {
                for (AccessibilityNodeInfo node : urlNodes) {
                    CharSequence text = node.getText();
                    if (text != null && text.length() > 0) {
                        String urlStr = text.toString().trim();
                        lastScannedUrl = urlStr;
                        urlBarRawText = urlStr;
                        if (urlStr.toLowerCase().contains("youtube.com") || urlStr.toLowerCase().contains("shorts")) {
                            isInsideYouTubeShortsTab = true;
                        }
                        String vid = extractVideoId(urlStr);
                        if (vid != null) {
                            urlBarVideoId = vid;
                            break;
                        }
                    }
                }
                recycleNodeList(urlNodes);
                if (urlBarVideoId != null) break;
            }
        }

        // 2. Deep search webview DOM nodes for swiped video IDs and visible captions
        List<String> foundDomIds = new ArrayList<>();
        List<String> visibleCaptions = new ArrayList<>();
        collectNodesRecursively(root, foundDomIds, visibleCaptions, 0, 20);

        Log.d(TAG, "Inspected: pkg=" + lastSeenPackage + " | urlBarId=" + urlBarVideoId + " | domIds=" + foundDomIds.size() + " | captions=" + visibleCaptions.size());

        // 3. Priority A: Check if a distinct video ID is present in the active DOM
        for (String domId : foundDomIds) {
            if (!domId.equalsIgnoreCase(urlBarVideoId) || currentVideoId == null) {
                String titleHint = !visibleCaptions.isEmpty() ? visibleCaptions.get(0) : null;
                handleShortDetected(domId, titleHint, null, "https://www.youtube.com/shorts/" + domId);
                return;
            }
        }

        // 4. Priority B: Check for visible video caption / title swipe changes
        if (isInsideYouTubeShortsTab && !visibleCaptions.isEmpty()) {
            String prominentTitle = visibleCaptions.get(0);

            if (currentTitle != null && !currentTitle.isEmpty()
                    && !currentTitle.equalsIgnoreCase(prominentTitle)
                    && !prominentTitle.contains("Loading")
                    && !prominentTitle.startsWith("YouTube Short (")) {

                Log.i(TAG, "Detected swipe to new title: " + prominentTitle);
                handleTitleTransition(prominentTitle);
                return;
            }
        }

        // 5. Priority C: Initial load from URL bar if no session is active yet
        if (currentVideoId == null && urlBarVideoId != null) {
            String titleHint = !visibleCaptions.isEmpty() ? visibleCaptions.get(0) : null;
            handleShortDetected(urlBarVideoId, titleHint, null, urlBarRawText);
        }
    }

    private void collectNodesRecursively(AccessibilityNodeInfo node, List<String> outIds, List<String> outCaptions, int depth, int maxDepth) {
        if (node == null || depth > maxDepth) return;

        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            String textStr = text.toString().trim();
            String vid = extractVideoId(textStr);
            if (vid != null) {
                outIds.add(vid);
            } else if (isValidShortTitle(textStr)) {
                outCaptions.add(textStr);
            }
        }

        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            String descStr = desc.toString().trim();
            String vid = extractVideoId(descStr);
            if (vid != null) {
                outIds.add(vid);
            } else if (isValidShortTitle(descStr)) {
                outCaptions.add(descStr);
            }
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectNodesRecursively(child, outIds, outCaptions, depth + 1, maxDepth);
                child.recycle();
            }
        }
    }

    private boolean isValidShortTitle(String text) {
        if (text == null || text.length() < 3 || text.length() > 200) return false;
        String lower = text.toLowerCase();
        if (lower.equals("shorts") || lower.equals("home") || lower.equals("subscriptions") || lower.equals("library")) return false;
        if (lower.contains("http://") || lower.contains("https://") || lower.contains("youtube.com")) return false;
        if (lower.equals("like") || lower.equals("dislike") || lower.equals("share") || lower.equals("comments") || lower.equals("remix") || lower.equals("subscribe")) return false;
        return true;
    }

    private String extractVideoId(String text) {
        if (text == null || text.isEmpty()) return null;

        for (Pattern pattern : VIDEO_ID_PATTERNS) {
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

    private void handleShortDetected(String videoId, String titleHint, String channelHint, String rawUrl) {
        long now = System.currentTimeMillis();

        if (videoId.equals(currentVideoId)) {
            if (titleHint != null && (currentTitle == null || currentTitle.contains("Loading") || currentTitle.startsWith("YouTube Short"))) {
                currentTitle = titleHint;
            }
            return;
        }

        // Finalize previous Short session
        if (currentVideoId != null) {
            finalizeCurrentSession("Swiped to new Short: " + videoId);
        }

        // Start new Short session
        currentVideoId = videoId;
        currentFullUrl = normalizeShortsUrl(videoId, rawUrl);
        sessionStartTimeMillis = now;
        currentTitle = titleHint != null ? titleHint : "Loading title...";
        currentChannelName = channelHint;
        isInsideYouTubeShortsTab = true;

        Log.i(TAG, ">>> TRACKING SHORT: " + videoId + " (" + currentFullUrl + ")");

        // Resolve official video metadata asynchronously
        YouTubeMetadataHelper.fetchVideoMetadata(videoId, info -> {
            if (videoId.equals(currentVideoId)) {
                currentTitle = info.title;
                currentChannelName = info.channelName;
                Log.i(TAG, ">>> TITLE RESOLVED: " + info.title + " by " + info.channelName);
                broadcastDiagnosticUpdate();
            }
        });

        broadcastDiagnosticUpdate();
    }

    private void handleTitleTransition(String newTitle) {
        long now = System.currentTimeMillis();
        if (newTitle == null || newTitle.equals(currentTitle)) return;

        if (currentVideoId != null) {
            finalizeCurrentSession("Swiped to new Short Title: " + newTitle);
        }

        currentVideoId = "title_" + Math.abs(newTitle.hashCode());
        currentTitle = newTitle;
        currentChannelName = null;
        currentFullUrl = "https://www.youtube.com/shorts/" + currentVideoId;
        sessionStartTimeMillis = now;

        Log.i(TAG, ">>> TRACKING SHORT BY TITLE: " + newTitle);
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
        String finishedTitle = currentTitle;
        String finishedChannel = currentChannelName;
        String finishedUrl = currentFullUrl;
        long startTimeMillis = sessionStartTimeMillis;

        // Reset state immediately
        currentVideoId = null;
        currentTitle = null;
        currentChannelName = null;
        currentFullUrl = null;
        sessionStartTimeMillis = 0L;

        Log.i(TAG, "=== FINALIZING SHORT: " + finishedVideoId + " (" + durationSeconds + "s). Reason: " + reason);

        if (durationSeconds < MIN_WATCH_DURATION_SECONDS) {
            Log.d(TAG, "Skipped Short under " + MIN_WATCH_DURATION_SECONDS + "s (" + durationSeconds + "s)");
            broadcastDiagnosticUpdate();
            return;
        }

        String deviceId = SupabaseConfig.getDeviceId(this);

        if (finishedVideoId.startsWith("title_")) {
            ShortSession session = new ShortSession(
                    finishedVideoId,
                    finishedTitle,
                    finishedChannel,
                    finishedUrl,
                    durationSeconds,
                    startTimeMillis,
                    endTimeMillis,
                    deviceId
            );
            dispatchSessionToSupabase(session);
        } else {
            YouTubeMetadataHelper.fetchVideoMetadata(finishedVideoId, info -> {
                String titleToSave = (info.title != null && !info.title.contains("Loading")) ? info.title : finishedTitle;
                String channelToSave = info.channelName != null ? info.channelName : finishedChannel;

                ShortSession session = new ShortSession(
                        finishedVideoId,
                        titleToSave,
                        channelToSave,
                        finishedUrl,
                        durationSeconds,
                        startTimeMillis,
                        endTimeMillis,
                        deviceId
                );
                dispatchSessionToSupabase(session);
            });
        }
    }

    private void dispatchSessionToSupabase(ShortSession session) {
        Log.i(TAG, ">>> DISPATCHING TO SUPABASE: " + session.getVideoId() + " (" + session.getDurationSeconds() + "s)");
        SupabaseClient.getInstance().insertShortSession(this, session, new SupabaseClient.ApiCallback() {
            @Override
            public void onSuccess(String responseBody) {
                Log.i(TAG, ">>> SUPABASE SYNC SUCCESS for " + session.getVideoId());
                broadcastSessionLogged(session, true, null);
            }

            @Override
            public void onFailure(String errorMessage) {
                Log.e(TAG, ">>> SUPABASE SYNC FAILURE: " + errorMessage);
                broadcastSessionLogged(session, false, errorMessage);
            }
        });

        broadcastSessionLogged(session, false, "Uploading to Supabase...");
        broadcastDiagnosticUpdate();
    }

    private void broadcastSessionLogged(ShortSession session, boolean synced, String errorMsg) {
        Intent intent = new Intent(ACTION_SESSION_LOGGED);
        intent.putExtra(EXTRA_VIDEO_ID, session.getVideoId());
        intent.putExtra(EXTRA_TITLE, session.getTitle());
        intent.putExtra(EXTRA_CHANNEL, session.getChannelName());
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
        intent.putExtra(EXTRA_DIAG_ACTIVE_TITLE, currentTitle != null ? currentTitle : "");
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
