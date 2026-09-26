package com.alan.fasttransfer.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import com.alan.fasttransfer.core.util.LocaleUtil;

import java.util.UUID;

/**
 * 应用设置（SharedPreferences 持久化）。
 */
public class AppSettings {

    private static final String PREFS = "fast_transfer_prefs";

    private static final String KEY_ALIAS = "alias";
    private static final String KEY_FINGERPRINT = "fingerprint";
    private static final String KEY_VISIBLE = "visible";
    private static final String KEY_PIN_ENABLED = "pin_enabled";
    private static final String KEY_PIN = "pin";
    private static final String KEY_PORT = "port";
    private static final String KEY_AUTO_ACCEPT = "auto_accept";
    private static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String KEY_HISTORY = "history_json";
    private static final String KEY_NIGHT_MODE = "night_mode";
    private static final String KEY_UI_SCALE_PERCENT = "ui_scale_percent";
    private static final String KEY_SAVE_TREE_URI = "save_tree_uri";
    private static final String KEY_SAVE_TREE_NAME = "save_tree_name";
    private static final String KEY_WEB_ENABLED = "web_enabled";

    /** 深色模式：跟随系统。 */
    public static final int NIGHT_SYSTEM = 0;
    /** 深色模式：始终开启。 */
    public static final int NIGHT_ON = 1;
    /** 深色模式：始终关闭。 */
    public static final int NIGHT_OFF = 2;

    private final SharedPreferences prefs;
    private final Context appContext;

    public AppSettings(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 应用级 Context，供网页传输等需要读文件列表的组件使用。 */
    public Context getContext() {
        return appContext;
    }

    public SharedPreferences raw() {
        return prefs;
    }

    public String getAlias() {
        String value = prefs.getString(KEY_ALIAS, null);
        if (value == null || value.trim().isEmpty()) {
            value = defaultAlias();
            prefs.edit().putString(KEY_ALIAS, value).apply();
        }
        return value;
    }

    public void setAlias(String alias) {
        prefs.edit().putString(KEY_ALIAS, alias == null ? "" : alias.trim()).apply();
    }

    /** 设备指纹，用于在发现阶段识别自己并避免回环。 */
    public String getFingerprint() {
        String value = prefs.getString(KEY_FINGERPRINT, null);
        if (value == null || value.isEmpty()) {
            value = UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString(KEY_FINGERPRINT, value).apply();
        }
        return value;
    }

    /** 显示名中使用的手机型号。 */
    public String getDeviceModel() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        String text = (manufacturer + " " + model).trim();
        return text.isEmpty() ? "Android" : text;
    }

    public boolean isVisible() {
        return prefs.getBoolean(KEY_VISIBLE, true);
    }

    public void setVisible(boolean visible) {
        prefs.edit().putBoolean(KEY_VISIBLE, visible).apply();
    }

    public boolean isPinEnabled() {
        return prefs.getBoolean(KEY_PIN_ENABLED, false);
    }

    public void setPinEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_PIN_ENABLED, enabled).apply();
    }

    public String getPin() {
        String value = prefs.getString(KEY_PIN, null);
        if (value == null || value.isEmpty()) {
            value = String.valueOf(100000 + (int) (Math.random() * 899999));
            prefs.edit().putString(KEY_PIN, value).apply();
        }
        return value;
    }

    public void setPin(String pin) {
        prefs.edit().putString(KEY_PIN, pin == null ? "" : pin.trim()).apply();
    }

    /** 当前生效的 PIN：未启用时返回 null。 */
    public String effectivePin() {
        return isPinEnabled() ? getPin() : null;
    }

    public int getPort() {
        return prefs.getInt(KEY_PORT, LocalsendProtocol.DEFAULT_PORT);
    }

    public void setPort(int port) {
        prefs.edit().putInt(KEY_PORT, port).apply();
    }

    public boolean isAutoAccept() {
        return prefs.getBoolean(KEY_AUTO_ACCEPT, false);
    }

    public void setAutoAccept(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_ACCEPT, value).apply();
    }

    public boolean isKeepScreenOn() {
        return prefs.getBoolean(KEY_KEEP_SCREEN_ON, true);
    }

    public void setKeepScreenOn(boolean value) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply();
    }

    public String getHistoryJson() {
        return prefs.getString(KEY_HISTORY, null);
    }

    public void setHistoryJson(String json) {
        prefs.edit().putString(KEY_HISTORY, json).apply();
    }

    public int getNightMode() {
        return prefs.getInt(KEY_NIGHT_MODE, NIGHT_SYSTEM);
    }

    public void setNightMode(int mode) {
        prefs.edit().putInt(KEY_NIGHT_MODE, mode).apply();
    }

    // ==================== 显示大小 ====================

    /** 显示大小：标准百分比。 */
    public static final int UI_SCALE_PERCENT_DEFAULT = 100;
    /** 允许的显示大小范围（百分比）。 */
    public static final int UI_SCALE_PERCENT_MIN = 50;
    public static final int UI_SCALE_PERCENT_MAX = 200;

    /** 显示大小百分比，100 表示标准。 */
    public int getUiScalePercent() {
        return clampUiScalePercent(
                prefs.getInt(KEY_UI_SCALE_PERCENT, UI_SCALE_PERCENT_DEFAULT));
    }

    public void setUiScalePercent(int percent) {
        prefs.edit().putInt(KEY_UI_SCALE_PERCENT, clampUiScalePercent(percent)).apply();
    }

    /** 把任意输入钳制到合法区间（纯函数，便于离线测试）。 */
    public static int clampUiScalePercent(int percent) {
        if (percent < UI_SCALE_PERCENT_MIN) {
            return UI_SCALE_PERCENT_MIN;
        }
        if (percent > UI_SCALE_PERCENT_MAX) {
            return UI_SCALE_PERCENT_MAX;
        }
        return percent;
    }

    // ==================== 保存位置 ====================

    /** 用户自定义的保存目录（SAF tree uri）；未设置时返回 null 表示用系统「下载」目录。 */
    public String getSaveTreeUri() {
        String value = prefs.getString(KEY_SAVE_TREE_URI, null);
        return value == null || value.isEmpty() ? null : value;
    }

    public void setSaveTreeUri(String uri) {
        prefs.edit().putString(KEY_SAVE_TREE_URI, uri == null ? "" : uri).apply();
    }

    /** 自定义目录的展示名，用于设置页显示。 */
    public String getSaveTreeName() {
        return prefs.getString(KEY_SAVE_TREE_NAME, "");
    }

    public void setSaveTreeName(String name) {
        prefs.edit().putString(KEY_SAVE_TREE_NAME, name == null ? "" : name).apply();
    }

    // ==================== 网页传输 ====================

    /**
     * 是否开启「网页传输」。
     *
     * <p>默认关闭：它会在同一端口上额外暴露一个浏览器可访问的页面，
     * 属于「用户没要求就不该开着」的功能。</p>
     */
    public boolean isWebEnabled() {
        return prefs.getBoolean(KEY_WEB_ENABLED, false);
    }

    public void setWebEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_WEB_ENABLED, enabled).apply();
    }

    private String defaultAlias() {
        String model = Build.MODEL;
        if (!TextUtils.isEmpty(model)) {
            return model.length() > 24 ? model.substring(0, 24) : model;
        }
        return LocaleUtil.isChinese() ? "我的设备" : "My device";
    }
}
