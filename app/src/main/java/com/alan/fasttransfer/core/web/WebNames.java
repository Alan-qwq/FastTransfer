package com.alan.fasttransfer.core.web;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * 网页传输里的纯字符串规则。
 *
 * <p>单独拎出来是因为这两条规则都直接关系到安全边界，必须能离线测试：</p>
 * <ul>
 *   <li>{@link #isSafeName} —— 下载接口只接受「一个纯文件名」。
 *       如果放行了 {@code ../} 或 {@code /}，电脑浏览器就能顺着
 *       {@code ?name=../../databases/xxx} 把 App 私有目录读走；</li>
 *   <li>{@link #urlEncode} —— 文件名可能含中文、空格、引号，
 *       拼进 {@code Content-Disposition} 和 {@code href} 前必须编码。</li>
 * </ul>
 */
public final class WebNames {

    private WebNames() {
    }

    /**
     * 是不是一个可以安全使用的文件名。
     *
     * <p>只允许「纯文件名」：不含路径分隔符、不是 {@code .} 或 {@code ..}、
     * 长度合理、不含控制字符。</p>
     */
    public static boolean isSafeName(String name) {
        if (name == null || name.isEmpty() || name.length() > 200) {
            return false;
        }
        if (".".equals(name) || "..".equals(name)) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\') {
                return false;
            }
            // 控制字符（含 NUL、换行）会让日志和 HTTP 头出问题
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        return true;
    }

    /** URL 编码，空格用 %20（不用 +，因为路径里 + 是字面量）。 */
    public static String urlEncode(String text) {
        if (text == null) {
            return "";
        }
        try {
            // URLEncoder 实现的是 application/x-www-form-urlencoded，比 RFC 3986 更激进：
            // 它会把 ~ 也编掉。~ 是 unreserved 字符，编了不算错但没必要，
            // 而且会让「文件名 -> URL -> 文件名」的往返看起来对不上，所以还原回来。
            return URLEncoder.encode(text, "UTF-8")
                    .replace("+", "%20")
                    .replace("%7E", "~")
                    .replace("%7e", "~");
        } catch (UnsupportedEncodingException e) {
            return text;
        }
    }

    /**
     * 这个路径要的是数据（JSON），而不是一个网页。
     *
     * <p>必须区分开：未授权时页面该回登录页 HTML，API 却必须回 401 JSON。
     * 早先两者混在一起，API 回了 200 的登录页，浏览器解析不出 JSON，
     * 表现成「点了接收也传不过去」，而且页面上看不到任何原因。</p>
     */
    public static boolean isApiPath(String path) {
        return "/web/prepare".equals(path)
                || "/web/prepare/status".equals(path)
                || "/web/upload".equals(path)
                || "/web/outbox".equals(path)
                || "/web/pull".equals(path);
    }

    /**
     * 给文件名生成 {@code Content-Disposition} 的值。
     *
     * <p>同时给出 {@code filename=} 和 {@code filename*=UTF-8''} 两种形式：
     * 前者给老浏览器，后者给懂 RFC 5987 的浏览器，中文名才不会变成乱码。</p>
     */
    public static String contentDisposition(String name) {
        String fallback = name == null ? "file" : name.replace("\"", "").replace("\\", "");
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + urlEncode(name);
    }
}
