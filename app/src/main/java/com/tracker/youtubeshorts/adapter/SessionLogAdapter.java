package com.tracker.youtubeshorts.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.tracker.youtubeshorts.R;
import com.tracker.youtubeshorts.model.ShortSession;

import java.util.ArrayList;
import java.util.List;

public class SessionLogAdapter extends RecyclerView.Adapter<SessionLogAdapter.LogViewHolder> {

    private final List<ShortSession> sessionList = new ArrayList<>();

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log, parent, false);
        return new LogViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        ShortSession session = sessionList.get(position);
        holder.bind(session);
    }

    @Override
    public int getItemCount() {
        return sessionList.size();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void addSession(ShortSession session) {
        // Check if session with same videoId and startedAt already exists in list (to update sync status)
        for (int i = 0; i < sessionList.size(); i++) {
            ShortSession existing = sessionList.get(i);
            if (existing.getVideoId().equals(session.getVideoId()) &&
                existing.getStartedAt().equals(session.getStartedAt())) {
                sessionList.set(i, session);
                notifyItemChanged(i);
                return;
            }
        }
        sessionList.add(0, session); // Prepend newest
        notifyItemInserted(0);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void clear() {
        sessionList.clear();
        notifyDataSetChanged();
    }

    static class LogViewHolder extends RecyclerView.ViewHolder {

        private final TextView tvTitle;
        private final TextView tvChannelAndId;
        private final TextView tvDuration;
        private final TextView tvUrl;
        private final TextView tvTimestamp;
        private final TextView tvSyncStatus;

        public LogViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvTitle);
            tvChannelAndId = itemView.findViewById(R.id.tvChannelAndId);
            tvDuration = itemView.findViewById(R.id.tvDuration);
            tvUrl = itemView.findViewById(R.id.tvUrl);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
            tvSyncStatus = itemView.findViewById(R.id.tvSyncStatus);
        }

        @SuppressLint("SetTextI18n")
        public void bind(ShortSession session) {
            Context context = itemView.getContext();

            // Set Title
            String title = session.getTitle();
            tvTitle.setText(title != null && !title.isEmpty() ? title : "YouTube Short (" + session.getVideoId() + ")");

            // Set Channel and ID
            String channel = session.getChannelName();
            if (channel != null && !channel.isEmpty()) {
                tvChannelAndId.setText(channel + " • ID: " + session.getVideoId());
            } else {
                tvChannelAndId.setText("Short ID: " + session.getVideoId());
            }

            // Duration
            tvDuration.setText(session.getDurationSeconds() + "s");

            // URL
            tvUrl.setText(session.getUrl());

            // Timestamp
            tvTimestamp.setText(formatTimeDisplay(session.getStartedAt(), session.getEndedAt()));

            // Sync Status
            if (session.isSynced()) {
                tvSyncStatus.setText("✓ Supabase Synced");
                tvSyncStatus.setTextColor(ContextCompat.getColor(context, R.color.status_green));
            } else if (session.getSyncError() != null) {
                tvSyncStatus.setText("✗ Error: " + session.getSyncError());
                tvSyncStatus.setTextColor(ContextCompat.getColor(context, R.color.status_red));
            } else {
                tvSyncStatus.setText("⏳ Syncing...");
                tvSyncStatus.setTextColor(Color.parseColor("#FFC107"));
            }
        }

        private String formatTimeDisplay(String startIso, String endIso) {
            try {
                String startPart = startIso.substring(startIso.indexOf('T') + 1, startIso.indexOf('.'));
                String endPart = endIso.substring(endIso.indexOf('T') + 1, endIso.indexOf('.'));
                return startPart + " - " + endPart + " UTC";
            } catch (Exception e) {
                return startIso;
            }
        }
    }
}
