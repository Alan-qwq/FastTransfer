package com.alan.fasttransfer.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

import com.alan.fasttransfer.core.AppSettings;

/**
 * 所有 Activity 的基类，负责应用「显示大小」。
 *
 * <p><b>坑点</b>：最初在 {@code attachBaseContext} 里用
 * {@code createConfigurationContext()} 返回了一个 ContextWrapper —— 它**不是 Activity**。
 * Fragment 的 {@code onAttach(Context)} 拿到的就是这个包装后的 context，
 * 于是 {@code getActivity()} 返回 null，Fragment 里 {@code settings} 之类的字段全为 null，
 * Activity 重建（改显示大小）时直接 NPE。</p>
 *
 * <p>正确做法：用 {@link #applyOverrideConfiguration} 直接改 Activity 自己的配置。
 * 这样 Activity 仍是 Activity，Fragment 拿到的也还是它。</p>
 */
public class BaseActivity extends androidx.appcompat.app.AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        // 必须先 super，再覆盖配置：applyOverrideConfiguration 只能在
        // attachBaseContext 阶段、且尚未访问资源时调用
        super.attachBaseContext(newBase);
        applyUiScaleOverride(newBase);
    }

    /** 把「显示大小」写进本 Activity 的配置覆盖。 */
    private void applyUiScaleOverride(Context base) {
        try {
            int percent = new AppSettings(base).getUiScalePercent();
            if (percent == AppSettings.UI_SCALE_PERCENT_DEFAULT) {
                return;
            }
            Configuration override = new Configuration();
            override.densityDpi = densityFor(base, percent);
            applyOverrideConfiguration(override);
        } catch (Throwable ignored) {
            // 覆盖失败不影响功能，只是显示大小不生效
        }
    }

    /**
     * 给 Context 应用显示大小。
     *
     * <p>只对**非 Activity** 的 Context 做包装 —— Activity 由
     * {@link #applyUiScaleOverride} 处理，包装它会把 Activity 变成普通 ContextWrapper。
     * 拿不到缩放（标准档）或转换失败时原样返回。</p>
     */
    public static Context applyUiScale(Context context) {
        if (context == null || context instanceof Activity) {
            return context;
        }
        try {
            int percent = new AppSettings(context).getUiScalePercent();
            if (percent == AppSettings.UI_SCALE_PERCENT_DEFAULT) {
                return context;
            }
            Configuration configuration =
                    new Configuration(context.getResources().getConfiguration());
            configuration.densityDpi = densityFor(context, percent);
            return context.createConfigurationContext(configuration);
        } catch (Throwable t) {
            return context;
        }
    }

    /**
     * 由百分比算出目标 density（纯函数，便于离线测试）。
     *
     * @param baseDensity 设备原始密度；&lt;=0 时回退到基准值
     * @param percent     显示大小百分比
     */
    public static int densityForPercent(int baseDensity, int percent) {
        int base = baseDensity > 0 ? baseDensity : 160;
        int density = Math.round(base * percent / 100f);
        // 参考 Android 自身对 density 的约束，避免极端值把布局算坏
        return Math.max(72, Math.min(density, 1200));
    }

    /**
     * 取用于缩放的基准密度。
     *
     * <p>必须取**应用 Context** 的原始密度，而不是当前 Activity 的 ——
     * 否则每次打开设置页都会在上一次缩放的基础上再乘一次，越改越大。</p>
     */
    private static int baseDensityOf(Context context) {
        try {
            Context appContext = context.getApplicationContext();
            if (appContext != null) {
                int density = appContext.getResources().getConfiguration().densityDpi;
                if (density > 0) {
                    return density;
                }
            }
        } catch (Throwable ignored) {
        }
        return context.getResources().getDisplayMetrics().densityDpi;
    }

    /** 由百分比算出目标 density。 */
    private static int densityFor(Context context, int percent) {
        return densityForPercent(baseDensityOf(context), percent);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 系统配置变化（旋转、深色切换）后重新应用一次缩放
        applyUiScaleOverride(this);
    }
}
