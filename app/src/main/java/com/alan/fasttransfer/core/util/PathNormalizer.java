package com.alan.fasttransfer.core.util;

/**
 * 把用户手写的存储路径归一成「卷:相对路径」（纯 Java，便于离线测试）。
 *
 * <p>Android 上同一个位置有多种写法：
 * {@code /sdcard}、{@code /storage/emulated/0}、{@code /storage/self/primary}、
 * {@code /mnt/sdcard} 其实是同一处。用户可能输入其中任意一种，甚至加上
 * {@code 外部存储/} 这样的中文前缀。归一到 {@code primary:Download/x} 这种形式后，
 * 才能拼出系统 DocumentsProvider 认得的 tree Uri。</p>
 */
public final class PathNormalizer {

    private PathNormalizer() {
    }

    /** AppSettings 里保存「手写路径」用的前缀。 */
    public static final String PATH_PREFIX = "path:";

    /**
     * 归一化。
     *
     * @param input       用户输入
     * @param primaryRoot 主存储根目录的真实路径，例如 {@code /storage/emulated/0}；
     *                    传 null 时只用内置别名
     * @return {@code 卷:相对路径}；无法识别返回 null
     */
    public static String normalize(String input, String primaryRoot) {
        if (input == null) {
            return null;
        }
        String path = input.trim().replace('\\', '/');
        if (path.isEmpty()) {
            return null;
        }
        if (path.startsWith(PATH_PREFIX)) {
            path = path.substring(PATH_PREFIX.length());
        }
        while (path.contains("//")) {
            path = path.replace("//", "/");
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty() || path.equals("/")) {
            return null;
        }

        // ① 已经是「卷:相对路径」（我们自己的存储格式）时原样返回，保证幂等。
        //    必须放在补前导斜杠之前，否则会被改成 "/primary:..." 再也认不出来。
        if (isNormalizedForm(path)) {
            return path;
        }

        // ② 去掉常见的路径前缀（中文写法 / 裸 sdcard）。
        //    这类写法剥离后得到的是「相对主存储」的路径，例如
        //    「外部存储/Download」-> Download，需要单独处理，
        //    不能当成绝对路径去匹配别名。
        boolean strippedPrefix = false;
        for (String prefix : new String[]{"外部存储", "内部存储", "手机存储", "sdcard"}) {
            if (path.startsWith(prefix + "/")) {
                path = trim(path.substring(prefix.length()));
                strippedPrefix = true;
                break;
            }
        }
        if (strippedPrefix) {
            return path.isEmpty() ? "primary:" : "primary:" + path;
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        // 主存储的常见别名
        String[] primaryAliases = {
                primaryRoot,
                "/sdcard",
                "/storage/emulated/0",
                "/storage/emulated/legacy",
                "/storage/self/primary",
                "/mnt/sdcard",
                "/mnt/shell/emulated/0",
        };
        for (String alias : primaryAliases) {
            if (alias == null || alias.isEmpty()) {
                continue;
            }
            if (path.equals(alias)) {
                return "primary:";
            }
            if (path.startsWith(alias + "/")) {
                return "primary:" + trim(path.substring(alias.length() + 1));
            }
        }

        // 可移动存储：/storage/XXXX-XXXX[/...]
        if (path.startsWith("/storage/")) {
            String rest = path.substring("/storage/".length());
            int slash = rest.indexOf('/');
            String volume = slash >= 0 ? rest.substring(0, slash) : rest;
            if (!volume.isEmpty()
                    && !"emulated".equals(volume)
                    && !"self".equals(volume)) {
                String sub = slash >= 0 ? trim(rest.substring(slash + 1)) : "";
                return volume + ":" + sub;
            }
        }
        return null;
    }

    /** 取卷名，例如 {@code primary}。 */
    public static String volumeOf(String normalized) {
        if (normalized == null) {
            return null;
        }
        int colon = normalized.indexOf(':');
        return colon < 0 ? null : normalized.substring(0, colon);
    }

    /** 取相对路径，例如 {@code Download/FastTransfer}。 */
    public static String relativePathOf(String normalized) {
        if (normalized == null) {
            return null;
        }
        int colon = normalized.indexOf(':');
        return colon < 0 ? null : normalized.substring(colon + 1);
    }

    /** 是否是主存储。 */
    public static boolean isPrimary(String normalized) {
        return "primary".equals(volumeOf(normalized));
    }

    /** 归一化结果拼回给人看的路径。 */
    public static String displayPathOf(String normalized, String primaryRoot) {
        String volume = volumeOf(normalized);
        String relative = relativePathOf(normalized);
        if (volume == null || relative == null) {
            return normalized == null ? "" : normalized;
        }
        String root;
        if ("primary".equals(volume)) {
            root = primaryRoot == null || primaryRoot.isEmpty() ? "/sdcard" : primaryRoot;
        } else {
            root = "/storage/" + volume;
        }
        return relative.isEmpty() ? root : root + "/" + relative;
    }

    /** 是否是「卷:相对路径」形式。 */
    private static boolean isNormalizedForm(String path) {
        int colon = path.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        for (int i = 0; i < colon; i++) {
            char c = path.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!allowed) {
                return false;
            }
        }
        // 冒号后必须是空或相对路径。注意「外部存储/Download」这种：
        // 冒号前虽然是合法字符，但冒号后以 '/' 开头，不是我们的格式
        return path.length() == colon + 1
                || path.charAt(colon + 1) != '/';
    }

    private static String trim(String value) {
        String result = value == null ? "" : value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
