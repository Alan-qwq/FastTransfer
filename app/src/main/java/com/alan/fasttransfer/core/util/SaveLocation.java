package com.alan.fasttransfer.core.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;

import com.alan.fasttransfer.core.AppSettings;

import java.io.File;

/**
 * 保存位置解析：把用户**手写的路径**翻译成可写入的目标。
 *
 * <p>难点在 Android 10+ 的分区存储：手写路径拿不到 SAF 授权，直接写文件会失败。
 * 做法是把路径归一到主存储的相对路径（见 {@link PathNormalizer}），
 * 再据此拼出标准目录的 tree Uri —— 这个 Uri 系统 DocumentsProvider 是认的
 * （例如「下载」目录），于是手写路径也能落盘。</p>
 */
public final class SaveLocation {

    private SaveLocation() {
    }

    /** 校验失败原因。 */
    public static final String ERROR_UNRECOGNIZED = "unrecognized";
    public static final String ERROR_NOT_WRITABLE = "not-writable";
    public static final String ERROR_NEEDS_PERMISSION = "needs-permission";

    /** AppSettings 里保存「手写路径」用的前缀。 */
    public static final String PATH_PREFIX = PathNormalizer.PATH_PREFIX;

    private static final String AUTHORITY = "com.android.externalstorage.documents";

    // ==================== 路径归一（转调纯函数实现） ====================

    /** @see PathNormalizer#normalize(String, String) */
    public static String normalize(String input) {
        return PathNormalizer.normalize(input, primaryRoot());
    }

    public static String volumeOf(String normalized) {
        return PathNormalizer.volumeOf(normalized);
    }

    public static String relativePathOf(String normalized) {
        return PathNormalizer.relativePathOf(normalized);
    }

    /** 归一化结果 -> 给人看的路径。 */
    public static String displayPathOf(String normalized) {
        return PathNormalizer.displayPathOf(normalized, primaryRoot());
    }

    private static String primaryRoot() {
        try {
            File root = Environment.getExternalStorageDirectory();
            return root == null ? null : root.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== tree Uri ====================

    /** 由归一化结果拼出 tree Uri。 */
    public static Uri treeUriOf(String normalized) {
        if (normalized == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return null;
        }
        try {
            return DocumentsContract.buildTreeDocumentUri(AUTHORITY, normalized);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 主存储相对路径 -> tree Uri。 */
    public static Uri primaryTreeUri(String relativePath) {
        String path = relativePath == null ? "" : relativePath;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return treeUriOf("primary:" + path);
    }

    /**
     * 当前保存位置对应的 tree Uri。
     *
     * <p>系统选择器授权过就直接用那个 Uri；手写路径则由路径推导。</p>
     */
    public static Uri currentTreeUri(AppSettings settings) {
        String stored = settings.getSaveTreeUri();
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        if (stored.startsWith("content:")) {
            return Uri.parse(stored);
        }
        return treeUriOf(normalize(stored));
    }

    /** 当前是否设置了自定义位置。 */
    public static boolean hasCustomLocation(AppSettings settings) {
        String stored = settings.getSaveTreeUri();
        return stored != null && !stored.isEmpty();
    }

    /** 当前保存位置是否来自「手写路径」（而不是系统选择器授权）。 */
    public static boolean isManualPath(AppSettings settings) {
        String stored = settings.getSaveTreeUri();
        return stored != null && stored.startsWith(PATH_PREFIX);
    }

    // ==================== 校验 ====================

    /** 校验结果。 */
    public static final class Validation {
        public boolean valid;
        public String displayPath = "";
        public String error;

        static Validation ok(String displayPath) {
            Validation result = new Validation();
            result.valid = true;
            result.displayPath = displayPath;
            return result;
        }

        static Validation fail(String error) {
            Validation result = new Validation();
            result.valid = false;
            result.error = error;
            return result;
        }
    }

    /**
     * 校验手写路径是否可作为保存位置。
     *
     * @param autoCreate 目录不存在时是否尝试创建
     */
    public static Validation validate(Context context, String input, boolean autoCreate) {
        String normalized = normalize(input);
        if (normalized == null) {
            return Validation.fail(ERROR_UNRECOGNIZED);
        }
        String relative = relativePathOf(normalized);
        if (relative == null) {
            return Validation.fail(ERROR_UNRECOGNIZED);
        }

        // 1) 先试 SAF：能列目录就说明这个位置可用（Android 10+ 走这条）
        Uri treeUri = treeUriOf(normalized);
        if (treeUri != null && canListTree(context, treeUri)) {
            if (autoCreate) {
                FileStorage.ensureChildFolder(context, treeUri, FileStorage.APP_FOLDER);
            }
            return Validation.ok(displayPathOf(normalized) + "/" + FileStorage.APP_FOLDER);
        }

        // 2) 退回文件路径（Android 9- 或已拿到「所有文件访问权限」）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasAllFilesAccess()) {
            File root = Environment.getExternalStorageDirectory();
            File dir = new File(root, relative.isEmpty() ? "" : relative);
            if (!dir.exists() && autoCreate && !dir.mkdirs()) {
                return Validation.fail(ERROR_NOT_WRITABLE);
            }
            if (dir.exists() && dir.isDirectory() && dir.canWrite()) {
                return Validation.ok(dir.getAbsolutePath());
            }
            return Validation.fail(ERROR_NOT_WRITABLE);
        }

        return Validation.fail(ERROR_NEEDS_PERMISSION);
    }

    /** tree 目录是否可以列出（能列即认为可用）。 */
    private static boolean canListTree(Context context, Uri treeUri) {
        if (treeUri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false;
        }
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            android.database.Cursor cursor = context.getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                    null, null, null);
            if (cursor == null) {
                return false;
            }
            cursor.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true;
        }
        try {
            return Environment.isExternalStorageManager();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 拼出「选择保存目录」的 Intent（带初始位置）。 */
    public static Intent createPickerIntent(String currentInput) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Uri initial = currentInput != null && currentInput.startsWith("content:")
                        ? Uri.parse(currentInput) : treeUriOf(normalize(currentInput));
                if (initial != null) {
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
                }
            } catch (Throwable ignored) {
            }
        }
        return intent;
    }
}
