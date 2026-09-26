package com.alan.fasttransfer.ui;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.alan.fasttransfer.R;
import com.alan.fasttransfer.core.transfer.TransferFile;
import com.alan.fasttransfer.core.transfer.TransferSession;
import com.alan.fasttransfer.core.util.Formatters;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

/**
 * 传输进度对话框：显示整体进度、当前文件、速度与剩余时间。
 */
public class TransferProgressDialog {

    public interface OnCancel {
        void onCancel();
    }

    private final Activity activity;
    private final TransferSession session;
    private final OnCancel cancelListener;

    private AlertDialog dialog;
    private TextView tvTitle;
    private TextView tvSubtitle;
    private TextView tvFile;
    private TextView tvPercent;
    private TextView tvSpeed;
    private TextView tvEta;
    private LinearProgressIndicator progressBar;
    private MaterialButton btnCancel;
    private MaterialButton btnClose;
    private ImageView icon;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastBytes;
    private long lastTime;
    private long speed;
    /** 已进入终态渲染：此后不再回到进行中界面。 */
    private boolean renderIsFinished;
    private boolean dismissed;

    public TransferProgressDialog(Activity activity, TransferSession session, OnCancel cancelListener) {
        this.activity = activity;
        this.session = session;
        this.cancelListener = cancelListener;
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    public void show() {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_transfer, null);
        tvTitle = view.findViewById(R.id.tv_transfer_title);
        tvSubtitle = view.findViewById(R.id.tv_transfer_subtitle);
        tvFile = view.findViewById(R.id.tv_transfer_file);
        tvPercent = view.findViewById(R.id.tv_transfer_percent);
        tvSpeed = view.findViewById(R.id.tv_transfer_speed);
        tvEta = view.findViewById(R.id.tv_transfer_eta);
        progressBar = view.findViewById(R.id.pb_transfer);
        btnCancel = view.findViewById(R.id.btn_transfer_cancel);
        btnClose = view.findViewById(R.id.btn_transfer_close);
        icon = view.findViewById(R.id.iv_transfer_icon);

        icon.setImageResource(session.direction == TransferSession.DIRECTION_SEND
                ? R.drawable.ic_send : R.drawable.ic_download);
        tvTitle.setText(session.direction == TransferSession.DIRECTION_SEND
                ? R.string.transfer_sending : R.string.transfer_receiving);
        tvSubtitle.setText(session.peer == null ? "" : session.peer.displayName());

        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cancelListener != null) {
                    cancelListener.onCancel();
                }
            }
        });
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
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
                dismissed = true;
            }
        });
        dialog.show();
        lastTime = System.currentTimeMillis();
        lastBytes = session.transferredBytes();
        update();
    }

    public void dismiss() {
        dismissed = true;
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
            }
        }
        dialog = null;
    }

    public boolean isDismissed() {
        return dismissed;
    }

    /** 会话状态变化时刷新界面（必须在主线程调用）。 */
    public void update() {
        if (dialog == null || !dialog.isShowing() || session == null) {
            return;
        }
        TransferSession.RenderState render = session.renderState();

        // 关键：一旦进入终态就只渲染终态，绝不再被后续的进度更新刷回「正在发送」。
        // 之前这里是无条件先画进行中、再判断 finished，所以完成后的任何一次
        // 更新都会把标题覆盖回「正在发送」。
        if (renderIsFinished) {
            renderFinished(render);
            return;
        }

        long total = session.totalBytes();
        long done = session.transferredBytes();

        long now = System.currentTimeMillis();
        long elapsed = now - lastTime;
        if (elapsed >= 400) {
            long delta = done - lastBytes;
            if (delta >= 0 && elapsed > 0) {
                long instant = delta * 1000 / elapsed;
                speed = speed == 0 ? instant : (speed * 2 + instant) / 3;
            }
            lastTime = now;
            lastBytes = done;
        }

        int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
        progressBar.setProgressCompat(total > 0 ? (int) Math.min(1000, done * 1000 / total) : 0, true);

        TransferFile active = currentActiveFile();
        if (active != null) {
            tvFile.setText(active.name);
        } else if (!session.files.isEmpty()) {
            tvFile.setText(session.files.get(session.files.size() - 1).name);
        }

        switch (render) {
            case PREPARING:
            case WAITING_PEER:
                tvTitle.setText(render == TransferSession.RenderState.PREPARING
                        ? R.string.transfer_preparing : R.string.transfer_waiting_peer);
                tvPercent.setText("");
                tvSpeed.setText("");
                tvEta.setText(getString(R.string.transfer_file_count, 0, session.fileCount()));
                return;

            case RUNNING:
                tvTitle.setText(session.direction == TransferSession.DIRECTION_SEND
                        ? R.string.transfer_sending : R.string.transfer_receiving);
                tvPercent.setText(percent + "%  ·  " + getString(R.string.transfer_file_count,
                        session.doneCount(), session.fileCount()));
                tvSpeed.setText(getString(R.string.transfer_speed, Formatters.speed(speed)));
                tvEta.setText(getString(R.string.transfer_eta,
                        Formatters.eta(Math.max(0, total - done), speed)));
                return;

            default:
                // 首帧就已是终态（例如瞬间被拒绝）
                renderIsFinished = true;
                renderFinished(render);
        }
    }

    /** 渲染终态；重复调用结果一致。 */
    private void renderFinished(TransferSession.RenderState render) {
        btnCancel.setVisibility(View.GONE);
        btnClose.setVisibility(View.VISIBLE);
        switch (render) {
            case DONE:
                tvTitle.setText(R.string.transfer_done);
                long duration = session.finishedAt - session.startedAt;
                tvSubtitle.setText(getString(R.string.transfer_done_detail,
                        session.fileCount(), Formatters.size(session.totalBytes()),
                        Formatters.duration(duration)));
                progressBar.setProgressCompat(1000, true);
                tvPercent.setText("100%");
                tvSpeed.setText(getString(R.string.transfer_speed,
                        Formatters.speed(speed)));
                tvEta.setText("");
                break;
            case CANCELLED:
                tvTitle.setText(R.string.transfer_cancelled);
                tvEta.setText("");
                break;
            case DECLINED:
                tvTitle.setText(R.string.transfer_declined);
                tvSubtitle.setText(describeError(session));
                tvEta.setText("");
                break;
            default:
                tvTitle.setText(R.string.transfer_failed);
                tvSubtitle.setText(describeError(session));
                tvEta.setText("");
                break;
        }
    }

    private String describeError(TransferSession session) {
        if (session.error == null) {
            return null;
        }
        Context context = activity;
        switch (session.error) {
            case "declined":
                return context.getString(R.string.error_rejected);
            case "pin-wrong":
                return context.getString(R.string.error_pin_wrong);
            case "pin-required":
                return context.getString(R.string.error_pin_required);
            case "pin-locked":
                return context.getString(R.string.error_pin_locked);
            case "busy":
                return context.getString(R.string.error_busy);
            case "timeout":
                return context.getString(R.string.error_connect_timeout);
            case "no-files-accepted":
                return context.getString(R.string.error_rejected);
            default:
                if (session.error.startsWith("http-")) {
                    return context.getString(R.string.error_unknown, session.error);
                }
                return session.error;
        }
    }

    private TransferFile currentActiveFile() {
        for (TransferFile file : session.files) {
            if (file.status == TransferFile.STATUS_ACTIVE) {
                return file;
            }
        }
        for (TransferFile file : session.files) {
            if (file.status == TransferFile.STATUS_PENDING) {
                return file;
            }
        }
        return null;
    }

    private String getString(int resId, Object... args) {
        return activity.getString(resId, args);
    }
}
