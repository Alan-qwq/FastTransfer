package com.alan.fasttransfer.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.alan.fasttransfer.BuildConfig;
import com.alan.fasttransfer.FastTransferApp;
import com.alan.fasttransfer.R;
import com.alan.fasttransfer.core.AppSettings;
import com.alan.fasttransfer.core.net.NetworkUtils;
import com.alan.fasttransfer.core.util.FileStorage;
import com.alan.fasttransfer.core.util.Logs;
import com.alan.fasttransfer.core.util.SaveLocation;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/**
 * 设置页（Material 3）。
 */
public class SettingsFragment extends BaseTabFragment {

    /** 选择保存目录的 SAF 请求码。 */
    private static final int REQUEST_SAVE_DIR = 5001;

    private TextInputLayout tilPin;
    private TextInputEditText etDeviceName;
    private TextInputEditText etPin;
    private TextInputEditText etPort;
    private MaterialSwitch swPin;
    private MaterialSwitch swAutoAccept;
    private MaterialSwitch swKeepScreen;
    private MaterialSwitch swWebTransfer;
    private TextView tvWebHint;
    private MaterialButton btnSaveDir;
    private MaterialButton btnSaveDirReset;
    private MaterialButton btnUiScale;
    private MaterialButton btnDarkMode;
    private TextView tvSaveDir;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    protected void onBindViews(View view, @Nullable Bundle savedInstanceState) {
        tilPin = view.findViewById(R.id.til_pin);
        etDeviceName = view.findViewById(R.id.et_device_name);
        etPin = view.findViewById(R.id.et_pin);
        etPort = view.findViewById(R.id.et_port);
        swPin = view.findViewById(R.id.sw_pin);
        swAutoAccept = view.findViewById(R.id.sw_auto_accept);
        swKeepScreen = view.findViewById(R.id.sw_keep_screen);
        swWebTransfer = view.findViewById(R.id.sw_web_transfer);
        tvWebHint = view.findViewById(R.id.tv_web_hint);
        btnSaveDir = view.findViewById(R.id.btn_save_dir);
        btnSaveDirReset = view.findViewById(R.id.btn_save_dir_reset);
        btnUiScale = view.findViewById(R.id.btn_ui_scale);
        btnDarkMode = view.findViewById(R.id.btn_dark_mode);
        tvSaveDir = view.findViewById(R.id.tv_settings_save_dir);
        TextView tvVersion = view.findViewById(R.id.tv_version);

        tvVersion.setText(getString(R.string.settings_version, BuildConfig.VERSION_NAME));

        // ---- 关于里的三个外链 ----
        bindLink(view, R.id.row_about_website, R.id.tv_about_website_value,
                R.string.settings_about_website_url);
        bindLink(view, R.id.row_about_github, R.id.tv_about_github_value,
                R.string.settings_about_github_url);
        bindLink(view, R.id.row_about_gitee, R.id.tv_about_gitee_value,
                R.string.settings_about_gitee_url);

        // ---- 设备名 ----
        etDeviceName.setText(settings().getAlias());
        etDeviceName.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                String value = s == null ? "" : s.toString().trim();
                if (value.isEmpty()) {
                    return;
                }
                settings().setAlias(value);
                if (ready()) {
                    engine().refreshLocalInfo();
                }
                if (host() != null) {
                    host().refreshHeader();
                }
            }
        });
        etDeviceName.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus && (etDeviceName.getText() == null
                        || etDeviceName.getText().toString().trim().isEmpty())) {
                    etDeviceName.setText(settings().getAlias());
                }
            }
        });

        // ---- PIN ----
        boolean pinEnabled = settings().isPinEnabled();
        swPin.setChecked(pinEnabled);
        etPin.setText(settings().getPin());
        tilPin.setVisibility(pinEnabled ? View.VISIBLE : View.GONE);
        swPin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean enabled = swPin.isChecked();
                settings().setPinEnabled(enabled);
                tilPin.setVisibility(enabled ? View.VISIBLE : View.GONE);
            }
        });
        etPin.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                String value = s == null ? "" : s.toString().trim();
                if (value.length() >= 4 && value.length() <= 8) {
                    settings().setPin(value);
                    tilPin.setError(null);
                    // 换了配对码，已发出去的网页会话要立刻失效
                    if (ready()) {
                        engine().receiveManager().onWebSettingsChanged();
                    }
                } else {
                    tilPin.setError(getString(R.string.settings_pin_invalid));
                }
            }
        });

        // ---- 端口 ----
        etPort.setText(String.valueOf(settings().getPort()));
        etPort.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                String value = s == null ? "" : s.toString().trim();
                if (value.isEmpty()) {
                    return;
                }
                try {
                    int port = Integer.parseInt(value);
                    if (port >= 1024 && port <= 65535) {
                        tilPortClearError();
                        if (port != settings().getPort()) {
                            applyPort(port);
                        }
                    } else {
                        tilPortError();
                    }
                } catch (NumberFormatException ignored) {
                    tilPortError();
                }
            }
        });
        etPort.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    etPort.setText(String.valueOf(settings().getPort()));
                    tilPortClearError();
                }
            }
        });

        // ---- 传输 ----
        swAutoAccept.setChecked(settings().isAutoAccept());
        swAutoAccept.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings().setAutoAccept(swAutoAccept.isChecked());
            }
        });

        swKeepScreen.setChecked(settings().isKeepScreenOn());
        swKeepScreen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings().setKeepScreenOn(swKeepScreen.isChecked());
            }
        });

        // ---- 网页传输 ----
        swWebTransfer.setChecked(settings().isWebEnabled());
        swWebTransfer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings().setWebEnabled(swWebTransfer.isChecked());
                if (ready()) {
                    engine().receiveManager().onWebSettingsChanged();
                    // 没在接收的话端口是空的，开关打开后把服务带起来才看得到地址
                    if (swWebTransfer.isChecked() && !engine().receiveManager().isRunning()) {
                        engine().receiveManager().start();
                    }
                }
                refreshWebHint();
            }
        });
        refreshWebHint();

        refreshDarkMode();
        btnDarkMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next;
                switch (settings().getNightMode()) {
                    case AppSettings.NIGHT_ON:
                        next = AppSettings.NIGHT_OFF;
                        break;
                    case AppSettings.NIGHT_OFF:
                        next = AppSettings.NIGHT_SYSTEM;
                        break;
                    default:
                        next = AppSettings.NIGHT_ON;
                        break;
                }
                settings().setNightMode(next);
                FastTransferApp.applyNightMode(next);
                refreshDarkMode();
            }
        });

        refreshUiScale();
        btnUiScale.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptUiScale();
            }
        });

        btnSaveDir.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSaveDirOptions();
            }
        });
        btnSaveDirReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetSaveDir();
            }
        });
        tvSaveDir.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSaveDirOptions();
            }
        });
        refreshSaveDir();
    }

    private void tilPortError() {
        View parent = getView();
        if (parent != null) {
            TextInputLayout layout = parent.findViewById(R.id.til_port);
            if (layout != null) {
                layout.setError(getString(R.string.settings_port_invalid));
            }
        }
    }

    private void tilPortClearError() {
        View parent = getView();
        if (parent != null) {
            TextInputLayout layout = parent.findViewById(R.id.til_port);
            if (layout != null) {
                layout.setError(null);
            }
        }
    }

    private void applyPort(int port) {
        settings().setPort(port);
        if (ready()) {
            engine().receiveManager().stop();
            engine().receiveManager().start();
            engine().refreshLocalInfo();
        }
    }

    private void refreshDarkMode() {
        if (btnDarkMode == null || settings() == null) {
            return;
        }
        switch (settings().getNightMode()) {
            case AppSettings.NIGHT_ON:
                btnDarkMode.setText(R.string.settings_dark_mode_on);
                break;
            case AppSettings.NIGHT_OFF:
                btnDarkMode.setText(R.string.settings_dark_mode_off);
                break;
            default:
                btnDarkMode.setText(R.string.settings_dark_mode_system);
                break;
        }
    }

    // ==================== 显示大小 ====================

    private void refreshUiScale() {
        if (btnUiScale == null || settings() == null) {
            return;
        }
        btnUiScale.setText(getString(R.string.settings_ui_scale_value,
                settings().getUiScalePercent()));
    }

    /** 输入百分比调整显示大小；density 变了需要重建界面。 */
    private void promptUiScale() {
        final int current = settings().getUiScalePercent();
        DialogHelper.promptNumber(requireActivity(),
                getString(R.string.settings_ui_scale),
                getString(R.string.settings_ui_scale_hint,
                        AppSettings.UI_SCALE_PERCENT_MIN, AppSettings.UI_SCALE_PERCENT_MAX),
                String.valueOf(current),
                AppSettings.UI_SCALE_PERCENT_MIN, AppSettings.UI_SCALE_PERCENT_MAX,
                new DialogHelper.OnNumber() {
                    @Override
                    public void onNumber(int value) {
                        if (value == current) {
                            return;
                        }
                        settings().setUiScalePercent(value);
                        if (getActivity() != null) {
                            getActivity().recreate();
                        }
                    }
                });
    }

    // ==================== 保存位置 ====================

    /** 选择保存位置的入口：手写路径 / 系统目录选择器。 */
    private void showSaveDirOptions() {
        final String[] options = {
                getString(R.string.settings_save_dir_input),
                getString(R.string.settings_save_dir_pick)
        };
        DialogHelper.choose(requireActivity(), getString(R.string.settings_save_dir),
                options, -1, new DialogHelper.OnText() {
                    @Override
                    public void onText(String value) {
                        if ("0".equals(value)) {
                            promptSaveDirPath();
                        } else {
                            pickSaveDirWithSystemUi();
                        }
                    }
                });
    }

    /** 手写路径：校验通过才写入设置。 */
    private void promptSaveDirPath() {
        final String current = settings().getSaveTreeUri();
        String initial = current == null ? "" : current;
        if (initial.startsWith("content:")) {
            initial = "";
        }
        DialogHelper.promptText(requireActivity(), getString(R.string.settings_save_dir_input),
                getString(R.string.settings_save_dir_input_hint),
                initial, InputType.TYPE_CLASS_TEXT, new DialogHelper.OnText() {
                    @Override
                    public void onText(String value) {
                        applyManualSaveDir(value);
                    }
                });
    }

    private void applyManualSaveDir(String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        String normalized = SaveLocation.normalize(value);
        if (normalized == null) {
            UiUtils.toast(getActivity(), R.string.settings_save_dir_invalid);
            return;
        }
        SaveLocation.Validation validation =
                SaveLocation.validate(requireContext(), value, true);
        if (!validation.valid) {
            // 区分「路径不认识」和「认得但写不进去」，提示才有用
            UiUtils.toast(getActivity(), "needs-permission".equals(validation.error)
                    ? R.string.settings_save_dir_need_permission
                    : R.string.settings_save_dir_invalid);
            return;
        }
        // 存归一化后的路径，取值时再推导 tree uri
        settings().setSaveTreeUri(SaveLocation.PATH_PREFIX + normalized);
        settings().setSaveTreeName(validation.displayPath);
        refreshSaveDir();
        UiUtils.toast(getActivity(), R.string.settings_save_dir_saved);
    }

    /** 打开系统目录选择器。 */
    private void pickSaveDirWithSystemUi() {
        try {
            startActivityForResult(
                    SaveLocation.createPickerIntent(settings().getSaveTreeUri()),
                    REQUEST_SAVE_DIR);
        } catch (Throwable t) {
            UiUtils.toast(getActivity(), R.string.settings_save_dir_cancelled);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SAVE_DIR) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri treeUri = data.getData();
        try {
            requireContext().getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable ignored) {
            // 部分实现只给读权限，也能落盘
        }
        settings().setSaveTreeUri(treeUri.toString());
        settings().setSaveTreeName(engine().storage().describeTreePath(
                android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri,
                        android.provider.DocumentsContract.getTreeDocumentId(treeUri))));
        refreshSaveDir();
        UiUtils.toast(getActivity(), R.string.settings_save_dir_saved);
    }

    private void resetSaveDir() {
        settings().setSaveTreeUri(null);
        settings().setSaveTreeName("");
        refreshSaveDir();
        UiUtils.toast(getActivity(), R.string.settings_save_dir_saved);
    }

    private void refreshSaveDir() {
        if (!isAdded() || !ready() || btnSaveDir == null) {
            return;
        }
        boolean custom = SaveLocation.hasCustomLocation(settings());
        btnSaveDir.setText(R.string.settings_save_dir_change);
        UiUtils.setVisible(btnSaveDirReset, custom);
        tvSaveDir.setText(engine().storage().describeSaveDir(
                settings().getSaveTreeUri(), settings().getSaveTreeName()));
    }

    /**
     * 刷新「网页传输」下面那行提示。
     *
     * <p>用户要的就是一个能敲进电脑浏览器的地址，所以开启后直接把
     * {@code http://<本机IP>:<端口>} 显示出来，不用再去别处找。</p>
     */
    private void refreshWebHint() {
        if (tvWebHint == null || !ready()) {
            return;
        }
        boolean enabled = settings().isWebEnabled();
        if (swWebTransfer != null && swWebTransfer.isChecked() != enabled) {
            swWebTransfer.setChecked(enabled);
        }
        if (!enabled) {
            tvWebHint.setText(R.string.settings_web_off);
            return;
        }
        String ip = NetworkUtils.getLocalIpv4();
        if (ip == null || ip.isEmpty()) {
            tvWebHint.setText(R.string.settings_web_need_server);
            return;
        }
        tvWebHint.setText(getString(R.string.settings_web_on) + "\nhttp://" + ip + ":"
                + engine().receiveManager().port() + "/");
    }

    @Override
    public void onShown() {
        if (!ready()) {
            return;
        }
        if (etDeviceName != null && !etDeviceName.hasFocus()) {
            etDeviceName.setText(settings().getAlias());
        }
        refreshSaveDir();
        refreshDarkMode();
        refreshUiScale();
        refreshWebHint();
    }

    /**
     * 把「关于」里一行外链绑好：显示地址去掉 https:// 前缀，点击用系统浏览器打开。
     *
     * <p>地址只从 string 资源取一份（{@code urlRes}），显示文案由它推导出来 ——
     * 这样显示的和实际跳转的不可能出现两处各写一份、改了一处忘了另一处的情况。
     */
    private void bindLink(View root, int rowId, int valueId, int urlRes) {
        final String url = getString(urlRes);
        TextView value = root.findViewById(valueId);
        value.setText(url.replaceFirst("^https?://", ""));
        root.findViewById(rowId).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl(url);
            }
        });
    }

    /**
     * 用系统浏览器打开外链。
     *
     * <p>刻意用隐式 Intent 交给浏览器，而不是内置 WebView：官网只是一个静态页面，
     * 为它多带一个 WebView 既涨体积又多一整套要维护的安全面。
     *
     * <p>没有浏览器时不能静默失败 —— 否则表现成「点了没反应」，用户只会以为是坏的。
     */
    private void openUrl(String url) {
        if (getContext() == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Logs.e(Logs.TAG, "打开链接失败: " + url, e);
            UiUtils.toast(getContext(), R.string.settings_about_link_failed);
        }
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}
