package com.alan.fasttransfer.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

/**
 * UI 小工具集合。
 */
public final class UiUtils {

    private UiUtils() {
    }

    public static void toast(Context context, String message) {
        if (context == null || message == null || message.isEmpty()) {
            return;
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    public static void toast(Context context, int resId) {
        if (context == null) {
            return;
        }
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show();
    }

    /** 复制文本到剪贴板。 */
    public static void copyToClipboard(Context context, String text) {
        if (context == null || text == null || text.isEmpty()) {
            return;
        }
        try {
            android.content.ClipboardManager manager = (android.content.ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(
                        android.content.ClipData.newPlainText("FastTransfer", text));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 带格式化参数的提示。 */
    public static void toast(Context context, int resId, Object... args) {
        if (context == null) {
            return;
        }
        Toast.makeText(context, context.getString(resId, args), Toast.LENGTH_SHORT).show();
    }

    public static void hideKeyboard(Context context, View view) {
        if (context == null || view == null) {
            return;
        }
        try {
            InputMethodManager imm = (InputMethodManager)
                    context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 屏幕宽度（dp），用于单双栏等自适应判断。 */
    public static int screenWidthDp(Context context) {
        if (context == null) {
            return 360;
        }
        Configuration configuration = context.getResources().getConfiguration();
        return configuration.screenWidthDp;
    }

    public static int smallestWidthDp(Context context) {
        if (context == null) {
            return 360;
        }
        return context.getResources().getConfiguration().smallestScreenWidthDp;
    }

    /** 超小屏（手表等）。 */
    public static boolean isTinyScreen(Context context) {
        return smallestWidthDp(context) < 240;
    }

    /** 宽屏（平板 / 词典笔）。 */
    public static boolean isWideScreen(Context context) {
        return screenWidthDp(context) >= 600;
    }

    public static void setVisible(View view, boolean visible) {
        if (view != null) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 给列表加一次「逐项淡入」入场动画。
     * 只在页面第一次显示时调用，避免每次数据刷新都跳一下。
     */
    public static void playListEntrance(View root, int recyclerViewId) {
        if (root == null) {
            return;
        }
        View child = root.findViewById(recyclerViewId);
        if (!(child instanceof androidx.recyclerview.widget.RecyclerView)) {
            return;
        }
        androidx.recyclerview.widget.RecyclerView list =
                (androidx.recyclerview.widget.RecyclerView) child;
        try {
            android.view.animation.LayoutAnimationController controller =
                    android.view.animation.AnimationUtils.loadLayoutAnimation(
                            list.getContext(), com.alan.fasttransfer.R.anim.layout_item_stagger);
            list.setLayoutAnimation(controller);
            list.scheduleLayoutAnimation();
        } catch (Throwable ignored) {
        }
    }

    /** 根据设备类型选择图标。 */
    public static int deviceIcon(String deviceType) {
        if (deviceType == null) {
            return com.alan.fasttransfer.R.drawable.ic_dev_default;
        }
        switch (deviceType) {
            case "mobile":
                return com.alan.fasttransfer.R.drawable.ic_dev_phone;
            case "desktop":
                return com.alan.fasttransfer.R.drawable.ic_dev_desktop;
            case "web":
                return com.alan.fasttransfer.R.drawable.ic_dev_web;
            case "server":
                return com.alan.fasttransfer.R.drawable.ic_dev_server;
            case "headless":
            default:
                return com.alan.fasttransfer.R.drawable.ic_dev_default;
        }
    }

    public static boolean isNightMode(Context context) {
        if (context == null) {
            return false;
        }
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int color(Context context, int resId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.getColor(resId);
        }
        //noinspection deprecation
        return context.getResources().getColor(resId);
    }
}
