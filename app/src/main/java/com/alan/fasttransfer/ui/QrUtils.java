package com.alan.fasttransfer.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import com.alan.fasttransfer.core.util.QrCode;

/**
 * 二维码渲染小工具：按控件尺寸在后台线程生成位图，避免阻塞 UI。
 */
public final class QrUtils {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private QrUtils() {
    }

    /** 取得控件当前可用于渲染的边长（像素），拿不到时给一个合理默认值。 */
    public static int resolveSize(ImageView view) {
        int width = view.getWidth();
        int height = view.getHeight();
        int side = Math.min(width, height);
        if (side <= 0) {
            side = (int) (200 * view.getResources().getDisplayMetrics().density);
        }
        return Math.max(160, side);
    }

    /**
     * 同步渲染（用于尺寸已知、内容很短的场景）。
     */
    public static Bitmap render(String content, int sizePx) {
        return QrCode.encode(content, sizePx);
    }

    /**
     * 在后台线程渲染并回填到 ImageView。
     *
     * @return 立即能算出来时返回位图，否则返回 null（稍后异步设置）
     */
    public static Bitmap renderForView(final String content, final ImageView view) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        final int size = resolveSize(view);
        // 二维码编码很快（几毫秒），小尺寸直接同步返回
        if (size <= 512) {
            return QrCode.encode(content, size);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = QrCode.encode(content, size);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (bitmap != null) {
                            view.setImageBitmap(bitmap);
                        }
                    }
                });
            }
        }, "qr-render").start();
        return null;
    }

    /** 打开全屏二维码界面。 */
    public static void showFullscreen(Context context, String content, String address) {
        try {
            Intent intent = new Intent(context, QrDisplayActivity.class);
            intent.putExtra(QrDisplayActivity.EXTRA_CONTENT, content);
            intent.putExtra(QrDisplayActivity.EXTRA_ADDRESS, address);
            context.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }
}
