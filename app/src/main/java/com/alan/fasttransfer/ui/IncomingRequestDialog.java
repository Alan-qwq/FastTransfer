package com.alan.fasttransfer.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alan.fasttransfer.R;
import com.alan.fasttransfer.core.LocalsendProtocol;
import com.alan.fasttransfer.core.ReceiveManager;
import com.alan.fasttransfer.core.transfer.TransferFile;
import com.alan.fasttransfer.core.util.Formatters;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/**
 * 收到传输请求时的确认对话框。
 */
public class IncomingRequestDialog {

    public interface OnDecision {
        void onDecision(String sessionId, boolean accepted, List<String> acceptedIds);
    }

    private final Activity activity;
    private AlertDialog dialog;

    public IncomingRequestDialog(Activity activity) {
        this.activity = activity;
    }

    public void show(final ReceiveManager.PendingRequest request, final OnDecision decision) {
        if (activity == null || activity.isFinishing() || request == null) {
            return;
        }
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_incoming, null);
        TextView tvFrom = view.findViewById(R.id.tv_incoming_from);
        TextView tvSummary = view.findViewById(R.id.tv_incoming_summary);
        TextView tvText = view.findViewById(R.id.tv_incoming_text);
        RecyclerView list = view.findViewById(R.id.rv_incoming_files);
        MaterialButton btnAccept = view.findViewById(R.id.btn_accept);
        MaterialButton btnDecline = view.findViewById(R.id.btn_decline);

        String senderName = request.sender == null ? "" : request.sender.displayName();
        tvFrom.setText(activity.getString(R.string.incoming_from, senderName));

        long total = 0;
        for (TransferFile file : request.files) {
            total += Math.max(0, file.size);
        }
        tvSummary.setText(activity.getString(R.string.incoming_summary,
                request.files.size(), Formatters.size(total)));

        // 单条文字消息直接展示内容
        String singleText = null;
        if (request.files.size() == 1) {
            TransferFile only = request.files.get(0);
            if (only.mime != null && only.mime.startsWith("text/")
                    && only.size <= LocalsendProtocol.MAX_TEXT_BYTES) {
                singleText = activity.getString(R.string.incoming_text_preview);
            }
        }
        if (singleText != null) {
            tvText.setVisibility(View.VISIBLE);
            tvText.setText(singleText);
        }

        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(new FileListAdapter(request.files));

        final AlertDialog[] holder = new AlertDialog[1];
        final boolean[] answered = {false};

        btnAccept.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                answered[0] = true;
                if (decision != null) {
                    decision.onDecision(request.sessionId, true, null);
                }
                dismiss();
            }
        });
        btnDecline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                answered[0] = true;
                if (decision != null) {
                    decision.onDecision(request.sessionId, false, null);
                }
                dismiss();
            }
        });

        dialog = new AlertDialog.Builder(activity)
                .setView(view)
                .setCancelable(false)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                if (!answered[0] && decision != null) {
                    decision.onDecision(request.sessionId, false, null);
                }
            }
        });
        dialog.show();
        holder[0] = dialog;
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
        dialog = null;
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    private static class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.Holder> {

        private final List<TransferFile> files;

        FileListAdapter(List<TransferFile> files) {
            this.files = new ArrayList<>(files);
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_incoming_file, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            TransferFile file = files.get(position);
            holder.name.setText(TextUtils.isEmpty(file.name)
                    ? holder.itemView.getContext().getString(R.string.incoming_text_preview)
                    : file.name);
            holder.size.setText(Formatters.size(file.size));
            boolean text = file.mime != null && file.mime.startsWith("text/");
            holder.icon.setImageResource(text ? R.drawable.ic_file : R.drawable.ic_file);
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView size;
            final ImageView icon;

            Holder(View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.tv_file_name);
                size = itemView.findViewById(R.id.tv_file_size);
                icon = itemView.findViewById(R.id.iv_file_icon);
            }
        }
    }
}
