package com.alan.fasttransfer.core.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

/**
 * 内容 URI / 文件相关的通用工具。
 */
public final class ContentUtils {

    private ContentUtils() {
    }

    /** 查询 content:// 的显示名与大小。 */
    public static String[] queryNameAndSize(Context context, Uri uri) {
        String name = null;
        long size = -1;
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex);
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (Throwable t) {
            Logs.d("ContentUtils", "query failed: " + t.getMessage());
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.isEmpty()) {
            name = "file";
        }
        return new String[]{name, String.valueOf(size)};
    }

    /** 从 URI 猜测 MIME。 */
    public static String guessMime(Context context, Uri uri, String name) {
        ContentResolver resolver = context.getContentResolver();
        if (resolver != null) {
            try {
                String type = resolver.getType(uri);
                if (type != null && !type.isEmpty()) {
                    return type;
                }
            } catch (Throwable ignored) {
            }
        }
        return MimeTypes.fromName(name);
    }

    /** 是否具备在旧版本上读取外部存储的权限。 */
    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("NewApi")
    public static boolean hasLegacyReadPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return context.checkCallingOrSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}
