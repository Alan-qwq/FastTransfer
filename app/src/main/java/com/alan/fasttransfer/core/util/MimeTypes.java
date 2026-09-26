package com.alan.fasttransfer.core.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 MIME 推断（不依赖任何外部库，覆盖常见扩展名）。
 */
public final class MimeTypes {

    private static final Map<String, String> MAP = new HashMap<>();

    static {
        MAP.put("jpg", "image/jpeg");
        MAP.put("jpeg", "image/jpeg");
        MAP.put("png", "image/png");
        MAP.put("gif", "image/gif");
        MAP.put("webp", "image/webp");
        MAP.put("bmp", "image/bmp");
        MAP.put("heic", "image/heic");
        MAP.put("svg", "image/svg+xml");
        MAP.put("mp4", "video/mp4");
        MAP.put("mkv", "video/x-matroska");
        MAP.put("mov", "video/quicktime");
        MAP.put("avi", "video/x-msvideo");
        MAP.put("3gp", "video/3gpp");
        MAP.put("webm", "video/webm");
        MAP.put("mp3", "audio/mpeg");
        MAP.put("wav", "audio/wav");
        MAP.put("flac", "audio/flac");
        MAP.put("aac", "audio/aac");
        MAP.put("ogg", "audio/ogg");
        MAP.put("m4a", "audio/mp4");
        MAP.put("amr", "audio/amr");
        MAP.put("pdf", "application/pdf");
        MAP.put("txt", "text/plain");
        MAP.put("log", "text/plain");
        MAP.put("md", "text/markdown");
        MAP.put("json", "application/json");
        MAP.put("xml", "application/xml");
        MAP.put("html", "text/html");
        MAP.put("htm", "text/html");
        MAP.put("css", "text/css");
        MAP.put("js", "application/javascript");
        MAP.put("zip", "application/zip");
        MAP.put("rar", "application/vnd.rar");
        MAP.put("7z", "application/x-7z-compressed");
        MAP.put("tar", "application/x-tar");
        MAP.put("gz", "application/gzip");
        MAP.put("apk", "application/vnd.android.package-archive");
        MAP.put("doc", "application/msword");
        MAP.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        MAP.put("xls", "application/vnd.ms-excel");
        MAP.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        MAP.put("ppt", "application/vnd.ms-powerpoint");
        MAP.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        MAP.put("epub", "application/epub+zip");
        MAP.put("csv", "text/csv");
        MAP.put("ttf", "font/ttf");
        MAP.put("otf", "font/otf");
        MAP.put("woff", "font/woff");
    }

    private MimeTypes() {
    }

    public static String fromName(String name) {
        if (name == null) {
            return "application/octet-stream";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "application/octet-stream";
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MAP.get(ext);
        return mime == null ? "application/octet-stream" : mime;
    }

    public static boolean isImage(String mime) {
        return mime != null && mime.startsWith("image/");
    }

    public static boolean isText(String mime) {
        return mime != null && mime.startsWith("text/");
    }
}
