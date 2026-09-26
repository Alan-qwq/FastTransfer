package com.alan.fasttransfer.core.util;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;

import java.util.EnumMap;
import java.util.Map;

/**
 * 二维码生成与解码（基于 ZXing core，纯 Java，不需要 Google Play 服务）。
 */
public final class QrCode {

    /** 扫码连接用的 URI scheme。 */
    public static final String SCHEME = "fasttransfer";
    public static final String HOST = "connect";

    private QrCode() {
    }

    // ==================== 生成 ====================

    /** 生成二维码位图；失败返回 null。 */
    public static Bitmap encode(String content, int sizePx) {
        if (content == null || content.isEmpty() || sizePx <= 0) {
            return null;
        }
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.ERROR_CORRECTION,
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);

            BitMatrix matrix = new MultiFormatWriter().encode(
                    content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (Throwable t) {
            Logs.w("QrCode", "encode failed: " + t.getMessage());
            return null;
        }
    }

    // ==================== 解码 ====================

    /**
     * 从相机预览帧（YUV 的 Y 平面，已按需旋转成竖直方向）解码。
     *
     * @param luminance 长度必须 &gt;= width * height
     */
    public static String decodeLuminance(byte[] luminance, int width, int height) {
        if (luminance == null || width <= 0 || height <= 0
                || luminance.length < width * height) {
            return null;
        }
        try {
            LuminanceSource source = new PlanarYUVLuminanceSource(
                    luminance, width, height, 0, 0, width, height, false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = reader().decode(bitmap, decodeHints());
            return result == null ? null : result.getText();
        } catch (Throwable t) {
            // 找不到二维码时 ZXing 会抛 NotFoundException，属于正常情况
            return null;
        }
    }

    private static MultiFormatReader sharedReader;

    private static synchronized MultiFormatReader reader() {
        if (sharedReader == null) {
            sharedReader = new MultiFormatReader();
        }
        return sharedReader;
    }

    private static Map<DecodeHintType, Object> decodeHints() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Arrays.asList(
                BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.CODE_128));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        return hints;
    }

    /**
     * 把 YUV 的 Y 平面整理成竖直方向的紧凑数组（宽高按旋转后为准）。
     *
     * @param rotationDegrees 0 / 90 / 180 / 270
     * @param outSize         输出 [width, height]
     */
    public static byte[] prepareLuminance(byte[] yPlane, int width, int height,
                                          int rowStride, int pixelStride,
                                          int rotationDegrees, int[] outSize) {
        if (yPlane == null || width <= 0 || height <= 0) {
            return null;
        }
        // 先抽出有效像素（rowStride 可能大于 width）
        byte[] packed = new byte[width * height];
        for (int y = 0; y < height; y++) {
            int srcRow = y * rowStride;
            int dstRow = y * width;
            if (pixelStride == 1) {
                int available = Math.min(width, yPlane.length - srcRow);
                if (available > 0) {
                    System.arraycopy(yPlane, srcRow, packed, dstRow, available);
                }
            } else {
                for (int x = 0; x < width; x++) {
                    int index = srcRow + x * pixelStride;
                    packed[dstRow + x] = index < yPlane.length ? yPlane[index] : 0;
                }
            }
        }

        int normalized = ((rotationDegrees % 360) + 360) % 360;
        if (normalized == 0) {
            outSize[0] = width;
            outSize[1] = height;
            return packed;
        }
        if (normalized == 180) {
            byte[] rotated = new byte[packed.length];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    rotated[(height - 1 - y) * width + (width - 1 - x)] = packed[y * width + x];
                }
            }
            outSize[0] = width;
            outSize[1] = height;
            return rotated;
        }
        // 90 / 270：输出尺寸互换
        int newWidth = height;
        int newHeight = width;
        byte[] rotated = new byte[packed.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int targetX;
                int targetY;
                if (normalized == 90) {
                    targetX = height - 1 - y;
                    targetY = x;
                } else {
                    targetX = y;
                    targetY = width - 1 - x;
                }
                rotated[targetY * newWidth + targetX] = packed[y * width + x];
            }
        }
        outSize[0] = newWidth;
        outSize[1] = newHeight;
        return rotated;
    }

    // ==================== 连接串 ====================

    /** 生成连接二维码内容：{@code fasttransfer://connect?host=…&port=…[&pin=…]} */
    public static String buildConnectContent(String host, int port, String pin) {
        StringBuilder sb = new StringBuilder();
        sb.append(SCHEME).append("://").append(HOST);
        sb.append("?host=").append(urlEncode(host == null ? "" : host));
        sb.append("&port=").append(port);
        if (pin != null && !pin.isEmpty()) {
            sb.append("&pin=").append(urlEncode(pin));
        }
        return sb.toString();
    }

    /** 扫描结果。 */
    public static class ConnectInfo {
        public String host;
        public int port;
        public String pin;
    }

    /**
     * 解析扫码结果。除了本应用自己的 URI，也兼容直接扫到 {@code 192.168.1.5:53317} 这种文本。
     *
     * @return 解析失败返回 null
     */
    public static ConnectInfo parseConnectContent(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        if (text.isEmpty()) {
            return null;
        }
        ConnectInfo info = new ConnectInfo();
        if (text.startsWith(SCHEME + "://")) {
            String query = text;
            int mark = text.indexOf('?');
            if (mark < 0) {
                return null;
            }
            query = text.substring(mark + 1);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = pair.substring(0, eq);
                String value = urlDecode(pair.substring(eq + 1));
                if ("host".equals(key)) {
                    info.host = value;
                } else if ("port".equals(key)) {
                    try {
                        info.port = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                    }
                } else if ("pin".equals(key)) {
                    info.pin = value;
                }
            }
            if (info.host == null || info.host.isEmpty()) {
                return null;
            }
            if (info.port <= 0) {
                info.port = com.alan.fasttransfer.core.LocalsendProtocol.DEFAULT_PORT;
            }
            return info;
        }

        // 兼容纯 "ip:port" 文本
        String[] hostPort = com.alan.fasttransfer.core.net.NetworkUtils.splitHostPort(
                text, com.alan.fasttransfer.core.LocalsendProtocol.DEFAULT_PORT);
        if (hostPort == null) {
            return null;
        }
        info.host = hostPort[0];
        try {
            info.port = Integer.parseInt(hostPort[1]);
        } catch (NumberFormatException ignored) {
        }
        return info;
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String urlDecode(String value) {
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
