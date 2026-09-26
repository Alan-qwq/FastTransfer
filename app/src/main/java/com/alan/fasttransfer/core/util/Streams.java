package com.alan.fasttransfer.core.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 流式 SHA-256（用于把本地文件读成文本等场景）。
 */
public final class Streams {

    private Streams() {
    }

    /** 限制最大读取长度，避免把大文件全读进内存。 */
    public static byte[] readLimited(InputStream in, int maxBytes) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(8192);
        byte[] chunk = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(chunk)) > 0) {
            if (total + n > maxBytes) {
                int allowed = maxBytes - total;
                if (allowed > 0) {
                    buffer.write(chunk, 0, allowed);
                }
                break;
            }
            buffer.write(chunk, 0, n);
            total += n;
        }
        return buffer.toByteArray();
    }

    public static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static String sha256(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) {
                digest.update(chunk, 0, n);
            }
            return toHex(digest.digest());
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    public static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }
}
