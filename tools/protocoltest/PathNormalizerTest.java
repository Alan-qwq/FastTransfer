import com.alan.fasttransfer.core.util.PathNormalizer;

/**
 * 保存位置路径归一化自测 —— 直接跑产品代码 {@link PathNormalizer}。
 *
 * <p>用户会用手写路径指定保存位置，写法五花八门：{@code /sdcard/...}、
 * {@code /storage/emulated/0/...}，甚至带中文前缀。归一是这条链路的根基，
 * 归一错了后面拼出的 tree Uri 就会指向不存在的位置，表现为「设置了但存不进去」。</p>
 */
public final class PathNormalizerTest {

    private static final String PRIMARY = "/storage/emulated/0";

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("=== 保存路径归一化 ===");

        // ---- 主存储的各种写法 ----
        check("/sdcard", "primary:", norm("/sdcard"));
        check("/storage/emulated/0", "primary:", norm("/storage/emulated/0"));
        check("/storage/self/primary", "primary:", norm("/storage/self/primary"));
        check("/mnt/sdcard", "primary:", norm("/mnt/sdcard"));
        check("/sdcard/", "primary:", norm("/sdcard/"));

        // ---- 子路径 ----
        check("/sdcard/Download", "primary:Download", norm("/sdcard/Download"));
        check("/storage/emulated/0/Download/FastTransfer",
                "primary:Download/FastTransfer",
                norm("/storage/emulated/0/Download/FastTransfer"));
        check("末尾斜杠被去掉", "primary:Download/FastTransfer",
                norm("/sdcard/Download/FastTransfer/"));
        check("重复斜杠被折叠", "primary:Download/x", norm("/sdcard//Download//x"));

        // ---- 中文 / 无斜杠前缀 ----
        check("外部存储前缀", "primary:Download", norm("外部存储/Download"));
        check("内部存储前缀", "primary:Pictures", norm("内部存储/Pictures"));
        check("sdcard 无斜杠前缀", "primary:Download", norm("sdcard/Download"));

        // ---- 带 path: 前缀（内部存储格式） ----
        check("path: 前缀被剥离", "primary:Download/FastTransfer",
                norm("path:/sdcard/Download/FastTransfer"));

        // ---- SD 卡 ----
        check("SD 卡根", "1234-5678:", norm("/storage/1234-5678"));
        check("SD 卡子目录", "1234-5678:Backup", norm("/storage/1234-5678/Backup"));
        check("emulated 不当成 SD 卡", "primary:Download",
                norm("/storage/emulated/0/Download"));

        // ---- 非法输入 ----
        check("null", null, norm(null));
        check("空串", null, norm(""));
        check("只有斜杠", null, norm("/"));
        check("相对路径", null, norm("Download/x"));
        check("不存在的卷", null, norm("/data/local/tmp"));
        check("只有中文前缀", null, norm("外部存储/"));

        // ---- 分解 ----
        check("卷名", "primary", PathNormalizer.volumeOf("primary:Download/x"));
        check("相对路径", "Download/x", PathNormalizer.relativePathOf("primary:Download/x"));
        check("根目录相对路径为空", "", PathNormalizer.relativePathOf("primary:"));
        check("是主存储", true, PathNormalizer.isPrimary("primary:Download"));
        check("不是主存储", false, PathNormalizer.isPrimary("1234-5678:Backup"));

        // ---- 拼回显示路径 ----
        check("显示路径", "/storage/emulated/0/Download/x",
                PathNormalizer.displayPathOf("primary:Download/x", PRIMARY));
        check("根目录显示路径", PRIMARY,
                PathNormalizer.displayPathOf("primary:", PRIMARY));
        check("无主存储信息时兜底", "/sdcard/Download",
                PathNormalizer.displayPathOf("primary:Download", null));

        // ---- 幂等：归一化两次结果一致 ----
        String once = norm("/sdcard/Download/FastTransfer");
        check("幂等", once, PathNormalizer.normalize(once, PRIMARY));

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static String norm(String input) {
        return PathNormalizer.normalize(input, PRIMARY);
    }

    private static void check(String label, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
            System.out.println("  PASS  " + label);
        } else {
            failed++;
            System.out.println("  FAIL  " + label);
            System.out.println("        expected: " + expected);
            System.out.println("        actual  : " + actual);
        }
    }
}
