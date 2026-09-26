package com.alan.fasttransfer.core.util;

import android.util.Log;

/**
 * 统一日志开关：debug 版本输出，release 版本静默。
 */
public final class Logs {

    public static final String TAG = "FastTransfer";

    private static volatile boolean enabled = false;

    private Logs() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void d(String message) {
        if (enabled) {
            Log.d(TAG, message);
        }
    }

    public static void d(String tag, String message) {
        if (enabled) {
            Log.d(TAG, tag + ": " + message);
        }
    }

    public static void w(String message) {
        Log.w(TAG, message);
    }

    public static void w(String tag, String message) {
        Log.w(TAG, tag + ": " + message);
    }

    public static void e(String message) {
        Log.e(TAG, message);
    }

    public static void e(String tag, String message) {
        Log.e(TAG, tag + ": " + message);
    }

    public static void e(String tag, String message, Throwable t) {
        Log.e(TAG, tag + ": " + message, t);
    }
}
