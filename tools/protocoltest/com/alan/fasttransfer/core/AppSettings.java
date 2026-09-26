package com.alan.fasttransfer.core;

/**
 * 仅供离线测试编译使用的 AppSettings 占位实现。
 *
 * <p>产品实现依赖 Android 的 Context / SharedPreferences，无法在纯 JVM 下编译。
 * 这里只保留测试需要、且与产品实现逐字一致的常量与纯函数
 * （{@link #clampUiScalePercent(int)} 与 AppSettings 中的实现相同）。</p>
 */
public class AppSettings {

    public static final int UI_SCALE_PERCENT_DEFAULT = 100;
    public static final int UI_SCALE_PERCENT_MIN = 50;
    public static final int UI_SCALE_PERCENT_MAX = 200;

    public static int clampUiScalePercent(int percent) {
        if (percent < UI_SCALE_PERCENT_MIN) {
            return UI_SCALE_PERCENT_MIN;
        }
        if (percent > UI_SCALE_PERCENT_MAX) {
            return UI_SCALE_PERCENT_MAX;
        }
        return percent;
    }
}
