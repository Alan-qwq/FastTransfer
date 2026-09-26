package com.alan.fasttransfer.core.transfer;

import android.content.Context;
import android.net.Uri;

import com.alan.fasttransfer.core.util.ContentUtils;
import com.alan.fasttransfer.core.util.FileNames;
import com.alan.fasttransfer.core.util.MimeTypes;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 待发送的一项内容（文件或文字）。
 */
public class SendItem {

    /** 本地文件。 */
    public File file;
    /** 内容 URI（SAF / 其他应用分享）。 */
    public Uri uri;
    public String name = "";
    public long size = -1;
    public String mime = "application/octet-stream";
    /** 纯文字消息的正文；为 null 表示普通文件。 */
    public String text;

    public static SendItem fromFile(File file) {
        SendItem item = new SendItem();
        item.file = file;
        item.name = file.getName();
        item.size = file.length();
        item.mime = MimeTypes.fromName(item.name);
        return item;
    }

    public static SendItem fromUri(Context context, Uri uri) {
        SendItem item = new SendItem();
        item.uri = uri;
        String[] meta = ContentUtils.queryNameAndSize(context, uri);
        item.name = FileNames.sanitize(meta[0]);
        try {
            item.size = Long.parseLong(meta[1]);
        } catch (NumberFormatException e) {
            item.size = -1;
        }
        item.mime = ContentUtils.guessMime(context, uri, item.name);
        return item;
    }

    public static SendItem fromText(String text) {
        SendItem item = new SendItem();
        item.name = com.alan.fasttransfer.core.LocalsendProtocol.TEXT_FILE_NAME;
        item.text = text;
        byte[] bytes;
        try {
            bytes = text.getBytes("UTF-8");
        } catch (Exception e) {
            bytes = text.getBytes();
        }
        item.size = bytes.length;
        item.mime = com.alan.fasttransfer.core.LocalsendProtocol.TEXT_MIME;
        return item;
    }

    public boolean isText() {
        return text != null;
    }

    public boolean isTextLike() {
        if (isText()) {
            return true;
        }
        return mime != null && mime.startsWith("text/") && size >= 0
                && size <= com.alan.fasttransfer.core.LocalsendProtocol.MAX_TEXT_BYTES;
    }

    /** 打开输入流；调用方负责关闭。 */
    public InputStream open(Context context) throws Exception {
        if (text != null) {
            return new java.io.ByteArrayInputStream(text.getBytes("UTF-8"));
        }
        if (file != null) {
            return new FileInputStream(file);
        }
        if (uri != null) {
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new java.io.FileNotFoundException("无法打开：" + uri);
            }
            return in;
        }
        throw new IllegalStateException("empty send item");
    }

    /** 拿不到大小时尝试用流探测（最多读 2MB 用于估算）。 */
    public long resolveSize(Context context) {
        if (size >= 0) {
            return size;
        }
        if (file != null) {
            size = file.length();
            return size;
        }
        InputStream in = null;
        try {
            in = open(context);
            long total = 0;
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) {
                total += n;
            }
            size = total;
        } catch (Throwable t) {
            size = -1;
        } finally {
            com.alan.fasttransfer.core.util.Streams.closeQuietly(in);
        }
        return size;
    }

    /** 是否是「可以当成一段文字发送」的小体积文本文件。 */
    public boolean looksLikeTextFile() {
        if (size < 0 || size > com.alan.fasttransfer.core.LocalsendProtocol.MAX_TEXT_BYTES) {
            return false;
        }
        if (text != null) {
            return true;
        }
        if (mime != null && mime.startsWith("text/")) {
            return true;
        }
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log")
                || lower.endsWith(".json") || lower.endsWith(".csv") || lower.endsWith(".xml");
    }

    /**
     * 读取文本内容（用于「选中的是文本文件时直接按文字发送」）。
     * 读取失败或体积超限时返回 null。
     */
    public String readTextContent(Context context) {
        if (text != null) {
            return text;
        }
        if (!looksLikeTextFile()) {
            return null;
        }
        InputStream in = null;
        try {
            in = open(context);
            byte[] data = com.alan.fasttransfer.core.util.Streams.readLimited(
                    in, com.alan.fasttransfer.core.LocalsendProtocol.MAX_TEXT_BYTES);
            return new String(data, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            com.alan.fasttransfer.core.util.Streams.closeQuietly(in);
        }
    }
}
