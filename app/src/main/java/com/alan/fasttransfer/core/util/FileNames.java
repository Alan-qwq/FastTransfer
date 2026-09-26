package com.alan.fasttransfer.core.util;

import java.io.File;

/**
 * 接收文件时的重名处理与非法字符清理。
 */
public final class FileNames {

    private FileNames() {
    }

    /** 去掉路径分隔符等非法字符。 */
    public static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "file";
        }
        String cleaned = name.replace('/', '_').replace('\\', '_').replace('\u0000', '_');
        cleaned = cleaned.replace("..", "_");
        cleaned = cleaned.trim();
        // Windows/安卓上都不宜过长
        if (cleaned.length() > 120) {
            int dot = cleaned.lastIndexOf('.');
            if (dot > 0 && cleaned.length() - dot <= 12) {
                cleaned = cleaned.substring(0, 100) + cleaned.substring(dot);
            } else {
                cleaned = cleaned.substring(0, 120);
            }
        }
        return cleaned.isEmpty() ? "file" : cleaned;
    }

    /** 在目录下生成不冲突的文件名：a.txt → a (1).txt。 */
    public static File uniqueFile(File dir, String name) {
        String safe = sanitize(name);
        File target = new File(dir, safe);
        if (!target.exists()) {
            return target;
        }
        String base = safe;
        String extension = "";
        int dot = safe.lastIndexOf('.');
        if (dot > 0) {
            base = safe.substring(0, dot);
            extension = safe.substring(dot);
        }
        for (int i = 1; i < 10_000; i++) {
            File candidate = new File(dir, base + " (" + i + ")" + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, System.currentTimeMillis() + "_" + safe);
    }

    /** 只保留文件名部分。 */
    public static String baseName(String path) {
        if (path == null) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
