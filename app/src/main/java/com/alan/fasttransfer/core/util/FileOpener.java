package com.alan.fasttransfer.core.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.alan.fasttransfer.R;

import java.util.List;

/**
 * 用其它应用打开收到的文件。
 *
 * <p>传输记录里点一条记录就跳去打开它 —— 这是用户最自然的期待：
 * 收到一张图就想看，收到一个安装包就想装，而不是自己去文件管理器里翻。</p>
 */
public final class FileOpener {

    private FileOpener() {
    }

    /**
     * 打开一个文件。
     *
     * @param uriString content:// 形式的位置；为空或打不开时给出提示
     * @return 成功发起 Intent 返回 true；否则 false（并已提示原因）
     */
    public static boolean open(Context context, String uriString, String mime) {
        if (context == null) {
            return false;
        }
        if (uriString == null || uriString.isEmpty()) {
            toast(context, R.string.history_open_no_file);
            return false;
        }
        Uri uri;
        try {
            uri = Uri.parse(uriString);
        } catch (Throwable t) {
            toast(context, R.string.history_open_failed);
            return false;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime == null || mime.isEmpty()
                ? "*/*" : mime);
        // 让接收方临时拿到读权限，否则对方一打开就 Permission denied
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // 从非 Activity 上下文启动时要带 NEW_TASK
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            // 没有应用能处理这种类型时，退一步用「打开方式」选择器
            return openWithChooser(context, uri, mime, t);
        }
    }

    private static boolean openWithChooser(Context context, Uri uri, String mime, Throwable cause) {
        try {
            Intent chooser = Intent.createChooser(buildView(uri, mime), null);
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            return true;
        } catch (Throwable t) {
            Logs.w("FileOpener", "open failed: " + cause + " / " + t);
            toast(context, R.string.history_open_no_app);
            return false;
        }
    }

    private static Intent buildView(Uri uri, String mime) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime == null || mime.isEmpty() ? "*/*" : mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    /**
     * 打开一批文件里的某一个。
     *
     * <p>一次只收到一个文件就直接开；收到多个时如果 UI 层没给用户选，
     * 这里默认开第一个 —— 总比什么都不做要好。</p>
     */
    public static boolean openFirst(Context context, List<String> uris, String mime) {
        if (uris == null || uris.isEmpty()) {
            toast(context, R.string.history_open_no_file);
            return false;
        }
        return open(context, uris.get(0), mime);
    }

    /** 分享一个文件。 */
    public static boolean share(Context context, String uriString, String mime, String title) {
        if (context == null || uriString == null || uriString.isEmpty()) {
            toast(context, R.string.history_open_no_file);
            return false;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mime == null || mime.isEmpty() ? "*/*" : mime);
            intent.putExtra(Intent.EXTRA_STREAM, Uri.parse(uriString));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(intent, title);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            return true;
        } catch (Throwable t) {
            Logs.w("FileOpener", "share failed: " + t);
            toast(context, R.string.history_open_no_app);
            return false;
        }
    }

    private static void toast(Context context, int resId) {
        try {
            Toast.makeText(context, resId, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
