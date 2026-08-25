package com.tracker.youtubeshorts;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.tracker.youtubeshorts.adapter.SessionLogAdapter;
import com.tracker.youtubeshorts.model.ShortSession;
import com.tracker.youtubeshorts.network.SupabaseClient;
import com.tracker.youtubeshorts.network.SupabaseConfig;
import com.tracker.youtubeshorts.service.EdgeShortsAccessibilityService;

public class MainActivity extends AppCompatActivity {

    private View viewStatusDot;
    private TextView tvServiceStatus;
    private Button btnToggleService;

    private TextInputEditText etSupabaseUrl;
    private TextInputEditText etSupabaseKey;
    private TextInputEditText etDeviceId;
    private Button btnSaveSettings;
    private Button btnTestConnection;

    private TextView tvEmptyLogs;
    private TextView btnClearLogs;
    private RecyclerView rvSessionLogs;
    private SessionLogAdapter logAdapter;

    private BroadcastReceiver sessionUpdateReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupRecyclerView();
        loadSavedConfig();
        setupListeners();
        registerSessionReceiver();
    }

    private void initViews() {
        viewStatusDot = findViewById(R.id.viewStatusDot);
        tvServiceStatus = findViewById(R.id.tvServiceStatus);
        btnToggleService = findViewById(R.id.btnToggleService);

        etSupabaseUrl = findViewById(R.id.etSupabaseUrl);
        etSupabaseKey = findViewById(R.id.etSupabaseKey);
        etDeviceId = findViewById(R.id.etDeviceId);
        btnSaveSettings = findViewById(R.id.btnSaveSettings);
        btnTestConnection = findViewById(R.id.btnTestConnection);

        tvEmptyLogs = findViewById(R.id.tvEmptyLogs);
        btnClearLogs = findViewById(R.id.btnClearLogs);
        rvSessionLogs = findViewById(R.id.rvSessionLogs);
    }

    private void setupRecyclerView() {
        logAdapter = new SessionLogAdapter();
        rvSessionLogs.setLayoutManager(new LinearLayoutManager(this));
        rvSessionLogs.setAdapter(logAdapter);
    }

    private void loadSavedConfig() {
        etSupabaseUrl.setText(SupabaseConfig.getSupabaseUrl(this));
        etSupabaseKey.setText(SupabaseConfig.getSupabaseKey(this));
        etDeviceId.setText(SupabaseConfig.getDeviceId(this));
    }

    private void setupListeners() {
        btnToggleService.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        btnSaveSettings.setOnClickListener(v -> {
            String url = etSupabaseUrl.getText() != null ? etSupabaseUrl.getText().toString().trim() : "";
            String key = etSupabaseKey.getText() != null ? etSupabaseKey.getText().toString().trim() : "";
            String deviceId = etDeviceId.getText() != null ? etDeviceId.getText().toString().trim() : "";

            if (TextUtils.isEmpty(url) || TextUtils.isEmpty(key)) {
                Toast.makeText(this, "Please enter both Supabase URL and Key", Toast.LENGTH_SHORT).show();
                return;
            }

            SupabaseConfig.saveConfig(this, url, key, deviceId);
            Toast.makeText(this, "Supabase settings saved!", Toast.LENGTH_SHORT).show();
        });

        btnTestConnection.setOnClickListener(v -> testSupabaseConnection());

        btnClearLogs.setOnClickListener(v -> {
            logAdapter.clear();
            updateEmptyState();
        });
    }

    private void testSupabaseConnection() {
        String url = etSupabaseUrl.getText() != null ? etSupabaseUrl.getText().toString().trim() : "";
        String key = etSupabaseKey.getText() != null ? etSupabaseKey.getText().toString().trim() : "";

        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(key)) {
            Toast.makeText(this, "Enter URL and Key before testing", Toast.LENGTH_SHORT).show();
            return;
        }

        btnTestConnection.setEnabled(false);
        btnTestConnection.setText("Testing...");

        SupabaseClient.getInstance().testConnection(url, key, new SupabaseClient.ApiCallback() {
            @Override
            public void onSuccess(String responseBody) {
                runOnUiThread(() -> {
                    btnTestConnection.setEnabled(true);
                    btnTestConnection.setText(R.string.btn_test_connection);
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Connection Successful")
                            .setMessage("Successfully connected to Supabase PostgreSQL table!")
                            .setPositiveButton("OK", null)
                            .show();
                });
            }

            @Override
            public void onFailure(String errorMessage) {
                runOnUiThread(() -> {
                    btnTestConnection.setEnabled(true);
                    btnTestConnection.setText(R.string.btn_test_connection);
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Connection Failed")
                            .setMessage(errorMessage)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
    }

    private void updateServiceStatus() {
        boolean isEnabled = isAccessibilityServiceEnabled(this, EdgeShortsAccessibilityService.class);
        if (isEnabled) {
            tvServiceStatus.setText(R.string.service_enabled);
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green));
            viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_green));
            btnToggleService.setText("Accessibility Settings");
        } else {
            tvServiceStatus.setText(R.string.service_disabled);
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red));
            viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_red));
            btnToggleService.setText(R.string.btn_enable_service);
        }
    }

    private boolean isAccessibilityServiceEnabled(Context context, Class<?> serviceClass) {
        String expectedServiceName = context.getPackageName() + "/" + serviceClass.getName();
        int accessibilityEnabled = 0;
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED
            );
        } catch (Settings.SettingNotFoundException e) {
            return false;
        }

        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            if (settingValue != null) {
                TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
                splitter.setString(settingValue);
                while (splitter.hasNext()) {
                    String service = splitter.next();
                    if (service.equalsIgnoreCase(expectedServiceName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerSessionReceiver() {
        sessionUpdateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;

                String videoId = intent.getStringExtra(EdgeShortsAccessibilityService.EXTRA_VIDEO_ID);
                String url = intent.getStringExtra(EdgeShortsAccessibilityService.EXTRA_URL);
                int duration = intent.getIntExtra(EdgeShortsAccessibilityService.EXTRA_DURATION, 0);
                String startedAt = intent.getStringExtra(EdgeShortsAccessibilityService.EXTRA_STARTED_AT);
                String endedAt = intent.getStringExtra(EdgeShortsAccessibilityService.EXTRA_ENDED_AT);
                boolean isSynced = intent.getBooleanExtra(EdgeShortsAccessibilityService.EXTRA_SYNCED, false);
                String error = intent.getStringExtra(EdgeShortsAccessibilityService.EXTRA_ERROR);

                if (videoId != null) {
                    ShortSession session = new ShortSession(
                            videoId,
                            url,
                            duration,
                            System.currentTimeMillis(),
                            System.currentTimeMillis(),
                            SupabaseConfig.getDeviceId(MainActivity.this)
                    );
                    session.setSynced(isSynced);
                    session.setSyncError(error);

                    logAdapter.addSession(session);
                    updateEmptyState();
                }
            }
        };

        IntentFilter filter = new IntentFilter(EdgeShortsAccessibilityService.ACTION_SESSION_LOGGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sessionUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(sessionUpdateReceiver, filter);
        }
    }

    private void updateEmptyState() {
        if (logAdapter.getItemCount() > 0) {
            tvEmptyLogs.setVisibility(View.GONE);
            rvSessionLogs.setVisibility(View.VISIBLE);
        } else {
            tvEmptyLogs.setVisibility(View.VISIBLE);
            rvSessionLogs.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sessionUpdateReceiver != null) {
            try {
                unregisterReceiver(sessionUpdateReceiver);
            } catch (Exception ignored) {}
        }
    }
}
