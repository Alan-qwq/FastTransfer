package com.alan.fasttransfer.core.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 接收文件的落盘策略：
 *
 * <ul>
 *   <li><b>用户自定义了目录</b>（SAF tree 授权）→ 写到该目录；</li>
 *   <li>否则 Android 10+ → 写公共「下载」目录（MediaStore，无需权限）；</li>
 *   <li>否则 Android 9- → 直接写公共「下载」目录（需要存储权限），
 *       连公共目录也不可写时退回应用目录兜底。</li>
 * </ul>
 */
public class FileStorage {

    public static final String APP_FOLDER = "FastTransfer";

    private final Context appContext;

    public FileStorage(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** 一次接收写入的目标。 */
    public static class Target {
        public OutputStream stream;
        /** 展示给用户的路径说明。 */
        public String displayPath = "";
        /** MediaStore 条目，写完后需要把 IS_PENDING 置 0。 */
        public Uri mediaUri;
        /** SAF 文档 Uri（自定义目录模式）。 */
        public Uri documentUri;
        public File file;
        public String textContent;
        /**
         * 可以直接交给其它应用打开的 Uri（content://）。
         *
         * <p>历史记录靠它实现「点一下用别的应用打开」。普通文件不能直接给
         * {@code file://} —— Android 7 起会抛 FileUriExposedException，
         * 所以统一在这里换成 FileProvider 的 content://。</p>
         */
        public Uri openUri;

        void finish(FileStorage storage) {
            if (mediaUri != null) {
                storage.publishPending(mediaUri);
            }
        }

        void delete(FileStorage storage) {
            Streams.closeQuietly(stream);
            try {
                if (mediaUri != null) {
                    storage.appContext.getContentResolver().delete(mediaUri, null, null);
                } else if (documentUri != null
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    DocumentsContract.deleteDocument(
                            storage.appContext.getContentResolver(), documentUri);
                } else if (file != null && file.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 打开一个写入目标。
     *
     * @param name        文件名
     * @param mime        MIME
     * @param destTreeUri 用户自定义目录（SAF tree uri，可为 null）
     */
    public Target openTarget(String name, String mime, Uri destTreeUri) throws IOException {
        return openTarget(name, mime, destTreeUri, false);
    }

    /**
     * 打开一个写入目标。
     *
     * @param nestInAppFolder 为 true 时先在该目录下建 {@link #APP_FOLDER} 子目录，
     *                        手写路径走这条，避免弄乱用户已有目录
     */
    public Target openTarget(String name, String mime, Uri destTreeUri,
                             boolean nestInAppFolder) throws IOException {
        String safeName = FileNames.sanitize(name);
        if (mime == null || mime.isEmpty()) {
            mime = MimeTypes.fromName(safeName);
        }

        // 1) 用户自定义目录优先
        if (destTreeUri != null) {
            Uri dir = nestInAppFolder
                    ? ensureChildFolder(appContext, destTreeUri, APP_FOLDER) : destTreeUri;
            Target target = openSafTarget(dir, safeName, mime);
            if (target != null) {
                return target;
            }
            Logs.w("FileStorage", "custom dir unusable, fallback to default");
        }

        // 2) Android 10+ 走 MediaStore 下载目录
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Target target = openMediaStoreTarget(safeName, mime);
            if (target != null) {
                return target;
            }
        }

        // 3) 文件路径
        File dir = publicDownloadsDir();
        if (dir == null) {
            dir = appDownloadsDir();
        }
        if (dir == null) {
            throw new IOException("无法创建保存目录");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            dir = appDownloadsDir();
            if (dir == null) {
                throw new IOException("无法创建保存目录");
            }
        }

        File targetFile = FileNames.uniqueFile(dir, safeName);
        Target result = new Target();
        result.file = targetFile;
        result.displayPath = targetFile.getAbsolutePath();
        result.stream = new FileOutputStream(targetFile);
        return result;
    }

    /** 写入完成后调用（把 MediaStore 条目从 pending 变为可见）。 */
    public void publish(Target target) {
        if (target != null) {
            resolveOpenUri(target);
            target.finish(this);
        }
    }

    /**
     * 给目标算出一个可以直接交给别的应用打开的 Uri。
     *
     * <p>顺序：MediaStore / SAF 的 content:// 直接用；普通文件走 FileProvider。
     * 都拿不到就留空，界面上会退化成「只显示保存位置」。</p>
     */
    private void resolveOpenUri(Target target) {
        if (target.openUri != null) {
            return;
        }
        if (target.mediaUri != null) {
            target.openUri = target.mediaUri;
            return;
        }
        if (target.documentUri != null) {
            target.openUri = target.documentUri;
            return;
        }
        if (target.file != null) {
            try {
                target.openUri = androidx.core.content.FileProvider.getUriForFile(
                        appContext, appContext.getPackageName() + ".fileprovider", target.file);
            } catch (Throwable t) {
                Logs.w("FileStorage", "FileProvider failed: " + t.getMessage());
            }
        }
    }

    /** 写入失败时删除残留。 */
    public void abandon(Target target) {
        if (target != null) {
            target.delete(this);
        }
    }

    // ==================== SAF 自定义目录 ====================

    private Target openSafTarget(Uri treeUri, String name, String mime) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || treeUri == null) {
            return null;
        }
        try {
            ContentResolver resolver = appContext.getContentResolver();
            Uri dirUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));

            // 重名时追加序号
            String candidateName = name;
            Uri created = null;
            for (int i = 0; i < 1000 && created == null; i++) {
                try {
                    created = DocumentsContract.createDocument(
                            resolver, dirUri, mime, candidateName);
                } catch (Throwable t) {
                    return null;
                }
                if (created == null) {
                    candidateName = suffixName(name, i + 1);
                }
            }
            if (created == null) {
                return null;
            }
            OutputStream stream = resolver.openOutputStream(created, "w");
            if (stream == null) {
                DocumentsContract.deleteDocument(resolver, created);
                return null;
            }
            Target target = new Target();
            target.documentUri = created;
            target.stream = stream;
            target.displayPath = describeTreePath(dirUri) + "/" + candidateName;
            return target;
        } catch (Throwable t) {
            Logs.w("FileStorage", "SAF target failed: " + t.getMessage());
            return null;
        }
    }

    private String suffixName(String name, int index) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            return name.substring(0, dot) + " (" + index + ")" + name.substring(dot);
        }
        return name + " (" + index + ")";
    }

    /** 把 SAF 文档 Uri 拼成可读路径。 */
    public String describeTreePath(Uri documentUri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return "";
        }
        try {
            String documentId = DocumentsContract.getDocumentId(documentUri);
            if (documentId == null) {
                return "";
            }
            int colon = documentId.indexOf(':');
            String volume = colon >= 0 ? documentId.substring(0, colon) : documentId;
            String path = colon >= 0 ? documentId.substring(colon + 1) : "";
            String prefix;
            if ("primary".equals(volume)) {
                File root = Environment.getExternalStorageDirectory();
                prefix = root == null ? "" : root.getAbsolutePath();
            } else {
                prefix = "/storage/" + volume;
            }
            return path.isEmpty() ? prefix : prefix + "/" + path;
        } catch (Throwable t) {
            return "";
        }
    }

    // ==================== MediaStore ====================

    /**
     * 通过反射访问 {@code MediaStore.Downloads}（API 29+）。
     * 既避免 minSdk 21 下的新 API 调用，也能在个别设备缺少 Downloads 集合时安全降级。
     */
    private static final class MediaStoreDownloads {
        static Uri collection;
        static String displayName;
        static String mimeType;
        static String relativePath;
        static String isPending;

        static boolean resolve() {
            if (collection != null) {
                return true;
            }
            try {
                Class<?> downloads = Class.forName("android.provider.MediaStore$Downloads");
                collection = (Uri) downloads.getField("EXTERNAL_CONTENT_URI").get(null);
                displayName = (String) downloads.getField("DISPLAY_NAME").get(null);
                mimeType = (String) downloads.getField("MIME_TYPE").get(null);
                relativePath = (String) downloads.getField("RELATIVE_PATH").get(null);
                isPending = (String) downloads.getField("IS_PENDING").get(null);
                return collection != null;
            } catch (Throwable t) {
                Logs.d("FileStorage", "MediaStore.Downloads unavailable: " + t.getMessage());
                return false;
            }
        }
    }

    private Target openMediaStoreTarget(String name, String mime) {
        try {
            if (!MediaStoreDownloads.resolve()) {
                return null;
            }
            ContentResolver resolver = appContext.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStoreDownloads.displayName, name);
            values.put(MediaStoreDownloads.mimeType, mime);
            values.put(MediaStoreDownloads.relativePath,
                    Environment.DIRECTORY_DOWNLOADS + "/" + APP_FOLDER);
            values.put(MediaStoreDownloads.isPending, 1);

            Uri item = resolver.insert(MediaStoreDownloads.collection, values);
            if (item == null) {
                return null;
            }
            OutputStream stream = resolver.openOutputStream(item);
            if (stream == null) {
                resolver.delete(item, null, null);
                return null;
            }
            Target target = new Target();
            target.mediaUri = item;
            target.stream = stream;
            target.displayPath = Environment.DIRECTORY_DOWNLOADS + "/" + APP_FOLDER + "/" + name;
            return target;
        } catch (Throwable t) {
            Logs.w("FileStorage", "MediaStore target failed: " + t.getMessage());
            return null;
        }
    }

    private void publishPending(Uri mediaUri) {
        try {
            if (!MediaStoreDownloads.resolve()) {
                return;
            }
            ContentValues values = new ContentValues();
            values.put(MediaStoreDownloads.isPending, 0);
            appContext.getContentResolver().update(mediaUri, values, null, null);
        } catch (Throwable t) {
            Logs.w("FileStorage", "publish failed: " + t.getMessage());
        }
    }

    // ==================== 默认目录 ====================

    /** 公共下载目录（Android 9- 需要存储权限）。 */
    public File publicDownloadsDir() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) {
                return null;
            }
            File target = new File(dir, APP_FOLDER);
            if (!target.exists() && !target.mkdirs()) {
                return dir.exists() ? dir : null;
            }
            return target;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 应用专属目录（仅在默认目录不可写时兜底，不再是用户可选项）。 */
    public File appDownloadsDir() {
        try {
            File base = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (base == null) {
                base = new File(appContext.getFilesDir(), "downloads");
            }
            File target = new File(base, APP_FOLDER);
            if (!target.exists() && !target.mkdirs()) {
                return base;
            }
            return target;
        } catch (Throwable t) {
            Logs.w("FileStorage", "app dir failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * 给 UI 显示的保存位置文案。
     *
     * @param customTreeUri 自定义目录；null 表示默认
     * @param customName    自定义目录的展示名
     */
    public String describeSaveDir(String customTreeUri, String customName) {
        if (customTreeUri != null && !customTreeUri.isEmpty()) {
            if (customName != null && !customName.isEmpty()) {
                return customName;
            }
            String path = describeTreeDocument(Uri.parse(customTreeUri));
            return path.isEmpty() ? customTreeUri : path;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return Environment.DIRECTORY_DOWNLOADS + "/" + APP_FOLDER;
        }
        File dir = publicDownloadsDir();
        if (dir != null) {
            return dir.getAbsolutePath();
        }
        File fallback = appDownloadsDir();
        return fallback == null ? "" : fallback.getAbsolutePath();
    }

    private String describeTreeDocument(Uri treeUri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return "";
        }
        try {
            Uri dirUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            return describeTreePath(dirUri);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 取目录下名为 {@link #APP_FOLDER} 的子目录；不存在就创建。
     *
     * <p>手写路径时用它把文件收进 FastTransfer 子目录，避免弄乱用户已有的目录。</p>
     *
     * @return 子目录 Uri；创建失败时返回原目录
     */
    public static Uri ensureChildFolder(Context context, Uri treeUri, String folderName) {
        if (treeUri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return treeUri;
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            Uri dirUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            android.database.Cursor cursor = resolver.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            }, null, null, null);
            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        if (folderName.equals(cursor.getString(1))) {
                            return DocumentsContract.buildDocumentUriUsingTree(
                                    treeUri, cursor.getString(0));
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
            Uri created = DocumentsContract.createDocument(resolver, dirUri,
                    DocumentsContract.Document.MIME_TYPE_DIR, folderName);
            return created != null ? created : treeUri;
        } catch (Throwable t) {
            Logs.d("FileStorage", "ensureChildFolder failed: " + t.getMessage());
            return treeUri;
        }
    }

    /** 构造「选择保存目录」的 Intent。 */
    public static Intent createSaveDirIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return intent;
    }
}
