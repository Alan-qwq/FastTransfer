package com.alan.fasttransfer.ui;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.alan.fasttransfer.R;
import com.alan.fasttransfer.core.transfer.HistoryEntry;
import com.alan.fasttransfer.core.transfer.TransferSession;
import com.alan.fasttransfer.core.util.Formatters;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 传输历史列表。
 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.Holder> {

    /** 点某一条记录。 */
    public interface OnEntryClick {
        void onEntryClick(HistoryEntry entry);
    }

    private final List<HistoryEntry> items = new ArrayList<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
    private OnEntryClick clickListener;

    public void setOnEntryClick(OnEntryClick listener) {
        this.clickListener = listener;
    }

    public void submit(List<HistoryEntry> entries) {
        items.clear();
        if (entries != null) {
            items.addAll(entries);
        }
        notifyDataSetChanged();
    }

    public int size() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final HistoryEntry entry = items.get(position);
        boolean sending = entry.direction == TransferSession.DIRECTION_SEND;

        holder.peer.setText(TextUtils.isEmpty(entry.peerAlias) ? entry.peerIp : entry.peerAlias);
        holder.icon.setImageResource(sending ? R.drawable.ic_send : R.drawable.ic_download);

        String direction = holder.itemView.getContext()
                .getString(sending ? R.string.history_direction_send
                        : R.string.history_direction_receive);
        String detail;
        if (entry.isText()) {
            detail = direction + " · " + entry.textPreview;
        } else {
            detail = direction + " · " + holder.itemView.getContext().getString(
                    R.string.history_summary, entry.fileCount, Formatters.size(entry.totalBytes));
            if (!TextUtils.isEmpty(entry.firstFileName) && entry.fileCount == 1) {
                detail = detail + " · " + entry.firstFileName;
            }
        }
        holder.detail.setText(detail);
        holder.time.setText(timeFormat.format(new Date(entry.finishedAt)));

        // 能打开的记录给一个明确的提示，否则用户不知道这里可以点
        boolean openable = entry.canOpen();
        UiUtils.setVisible(holder.open, openable);
        holder.itemView.setClickable(openable);
        holder.itemView.setOnClickListener(openable ? new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (clickListener != null) {
                    clickListener.onEntryClick(entry);
                }
            }
        } : null);

        int statusRes;
        int colorRes;
        if ("success".equals(entry.statusKey)) {
            statusRes = R.string.history_status_success;
            colorRes = R.color.success;
        } else if ("cancelled".equals(entry.statusKey)) {
            statusRes = R.string.history_status_cancelled;
            colorRes = R.color.warning;
        } else if ("declined".equals(entry.statusKey)) {
            statusRes = R.string.history_status_declined;
            colorRes = R.color.warning;
        } else if ("running".equals(entry.statusKey)) {
            statusRes = R.string.history_status_running;
            colorRes = R.color.info;
        } else {
            statusRes = R.string.history_status_failed;
            colorRes = R.color.danger;
        }
        holder.status.setText(statusRes);
        holder.status.setTextColor(UiUtils.color(holder.itemView.getContext(), colorRes));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView peer;
        final TextView detail;
        final TextView time;
        final TextView status;
        final ImageView icon;
        final View open;

        Holder(View itemView) {
            super(itemView);
            peer = itemView.findViewById(R.id.tv_history_peer);
            detail = itemView.findViewById(R.id.tv_history_detail);
            time = itemView.findViewById(R.id.tv_history_time);
            status = itemView.findViewById(R.id.tv_history_status);
            icon = itemView.findViewById(R.id.iv_direction);
            open = itemView.findViewById(R.id.tv_history_open);
        }
    }
}
