package com.alan.fasttransfer.core.util;

/**
 * JVM 协议测试用的 Logs 替身（Android 的 android.util.Log 在桌面上不可用）。
 */
public final class Logs {

    private static boolean enabled = true;

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
            System.out.println("[D] " + message);
        }
    }

    public static void d(String tag, String message) {
        if (enabled) {
            System.out.println("[D] " + tag + ": " + message);
        }
    }

    public static void w(String message) {
        System.out.println("[W] " + message);
    }

    public static void w(String tag, String message) {
        System.out.println("[W] " + tag + ": " + message);
    }

    public static void e(String message) {
        System.out.println("[E] " + message);
    }

    public static void e(String tag, String message) {
        System.out.println("[E] " + tag + ": " + message);
    }

    public static void e(String tag, String message, Throwable t) {
        System.out.println("[E] " + tag + ": " + message);
        t.printStackTrace(System.out);
    }
}
