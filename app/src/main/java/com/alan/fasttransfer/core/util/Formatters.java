package com.alan.fasttransfer.core.util;

import java.util.Locale;

/**
 * 体积 / 速度 / 时间的显示格式。
 */
public final class Formatters {

    private Formatters() {
    }

    public static String size(long bytes) {
        if (bytes < 0) {
            return "--";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.2f GB", gb);
    }

    public static String speed(long bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            return "--";
        }
        return size(bytesPerSecond);
    }

    /** 把毫秒格式化成 "1分23秒" / "12秒" 这一类短文本。 */
    public static String duration(long millis) {
        if (millis < 0) {
            return "--";
        }
        long totalSeconds = millis / 1000;
        if (totalSeconds < 60) {
            return totalSeconds + (LocaleUtil.isChinese() ? " 秒" : "s");
        }
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes < 60) {
            return LocaleUtil.isChinese()
                    ? minutes + " 分 " + seconds + " 秒"
                    : minutes + "m " + seconds + "s";
        }
        long hours = minutes / 60;
        minutes = minutes % 60;
        return LocaleUtil.isChinese()
                ? hours + " 小时 " + minutes + " 分"
                : hours + "h " + minutes + "m";
    }

    /** 传输剩余时间的估算文本。 */
    public static String eta(long remainingBytes, long bytesPerSecond) {
        if (bytesPerSecond <= 0 || remainingBytes <= 0) {
            return "--";
        }
        return duration(remainingBytes * 1000 / bytesPerSecond);
    }

    public static String percent(long done, long total) {
        if (total <= 0) {
            return "0%";
        }
        int value = (int) Math.min(100, done * 100 / total);
        return value + "%";
    }
}
