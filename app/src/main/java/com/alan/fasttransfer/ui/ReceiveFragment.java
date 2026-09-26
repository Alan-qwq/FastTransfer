package com.alan.fasttransfer.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.alan.fasttransfer.R;
import com.alan.fasttransfer.core.util.FileStorage;
import com.alan.fasttransfer.core.util.QrCode;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 接收页（Material 3）：可见性开关、本机地址、连接二维码、保存位置与使用提示。
 */
public class ReceiveFragment extends BaseTabFragment {

    private MaterialSwitch swVisible;
    private TextView tvVisibilityState;
    private TextView tvVisibilityDesc;
    private TextView tvAddress;
    private TextView tvPort;
    private TextView tvSaveDir;
    private ImageView ivQr;
    private TextView tvQrHint;
    private MaterialButton btnQrLarger;

    /** 当前二维码内容与地址，供「放大」使用。 */
    private String qrContent = "";
    private String qrAddress = "";
    /** 上次渲染二维码时的地址，避免每次刷新都重新编码。 */
    private String lastQrKey = "";

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_receive, container, false);
    }

    @Override
    protected void onBindViews(View view, @Nullable Bundle savedInstanceState) {
        swVisible = view.findViewById(R.id.sw_visible);
        tvVisibilityState = view.findViewById(R.id.tv_visibility_state);
        tvVisibilityDesc = view.findViewById(R.id.tv_visibility_desc);
        tvAddress = view.findViewById(R.id.tv_local_address);
        tvPort = view.findViewById(R.id.tv_local_port);
        tvSaveDir = view.findViewById(R.id.tv_save_dir);
        ivQr = view.findViewById(R.id.iv_qr);
        tvQrHint = view.findViewById(R.id.tv_qr_hint);
        btnQrLarger = view.findViewById(R.id.btn_qr_larger);

        swVisible.setChecked(ready() && engine().isVisible());
        swVisible.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!ready()) {
                    return;
                }
                engine().setVisible(swVisible.isChecked());
                if (host() != null) {
                    host().refreshHeader();
                }
                refresh();
            }
        });

        view.findViewById(R.id.btn_copy_address).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyAddress();
            }
        });
        view.findViewById(R.id.tv_save_dir).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSaveDirInfo();
            }
        });

        View.OnClickListener enlarge = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showQrFullscreen();
            }
        };
        ivQr.setOnClickListener(enlarge);
        btnQrLarger.setOnClickListener(enlarge);

        refresh();
    }

    private void showQrFullscreen() {
        if (qrContent.isEmpty()) {
            return;
        }
        QrUtils.showFullscreen(requireContext(), qrContent, qrAddress);
    }

    private void copyAddress() {
        if (!ready()) {
            return;
        }
        String ip = engine().localIp();
        if (ip == null) {
            UiUtils.toast(getActivity(), R.string.local_ip_unknown);
            return;
        }
        String address = ip + ":" + engine().localPort();
        try {
            android.content.ClipboardManager manager = (android.content.ClipboardManager)
                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(android.content.ClipData.newPlainText("FastTransfer", address));
                UiUtils.toast(getActivity(), R.string.action_copied);
            }
        } catch (Throwable ignored) {
        }
    }

    private void showSaveDirInfo() {
        if (!ready()) {
            return;
        }
        FileStorage storage = engine().storage();
        String path = storage.describeSaveDir(settings().getSaveTreeUri(),
                settings().getSaveTreeName());
        DialogHelper.info(getActivity(), path);
    }

    @Override
    public void onShown() {
        refresh();
    }

    private void refresh() {
        if (!isAdded() || !ready() || tvAddress == null) {
            return;
        }
        boolean visible = engine().isVisible();
        swVisible.setChecked(visible);
        tvVisibilityState.setText(visible ? R.string.visibility_on : R.string.visibility_off);
        tvVisibilityDesc.setText(visible ? R.string.receive_desc : R.string.visibility_off_desc);

        String ip = engine().localIp();
        int port = engine().localPort();
        tvAddress.setText(ip == null ? getString(R.string.local_ip_unknown) : ip);
        tvPort.setText(getString(R.string.receive_port, port));
        tvSaveDir.setText(engine().storage().describeSaveDir(settings().getSaveTreeUri(),
                settings().getSaveTreeName()));

        refreshQr(ip, port);
    }

    /** 生成/刷新连接二维码。 */
    private void refreshQr(String ip, int port) {
        if (ip == null || ip.isEmpty()) {
            qrContent = "";
            qrAddress = "";
            lastQrKey = "";
            ivQr.setImageResource(R.drawable.ic_qr);
            tvQrHint.setText(R.string.qr_card_no_network);
            btnQrLarger.setEnabled(false);
            return;
        }
        String pin = settings().effectivePin();
        String key = ip + ":" + port + "|" + (pin == null ? "" : pin);
        if (key.equals(lastQrKey)) {
            return;
        }
        lastQrKey = key;

        qrContent = QrCode.buildConnectContent(ip, port, pin);
        qrAddress = ip + ":" + port;
        tvQrHint.setText(getString(R.string.qr_card_hint, qrAddress));
        btnQrLarger.setEnabled(true);

        int side = QrUtils.resolveSize(ivQr);
        android.graphics.Bitmap bitmap = QrUtils.render(qrContent, side);
        if (bitmap != null) {
            ivQr.setImageBitmap(bitmap);
        }
    }
}
