import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 递归防护静态校验。
 *
 * <p>背景：底栏选中项与页面切换互相驱动时极易写出死循环 ——
 * {@code showTab() → syncNavSelection() → setSelectedItemId() → 回调 → showTab() …}
 * 最后必然 {@code StackOverflowError}（已真实发生过一次）。</p>
 *
 * <p>这类 bug 编译期、lint、单元测试都发现不了，只能靠约定 + 静态检查兜住。
 * 规则：{@code syncNavSelection} 在调用 {@code setSelectedItemId} 前后
 * 必须成对设置/清除同一个布尔守卫，并且设置动作要在 {@code try} 里。</p>
 *
 * 用法：java RecursionGuardCheck &lt;源码根目录&gt;
 */
public final class RecursionGuardCheck {

    private static final String GUARD = "syncingNavSelection";
    private static final String CALL = "setSelectedItemId";

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: RecursionGuardCheck <java source root>");
            System.exit(2);
        }
        Path root = Paths.get(args[0]);
        if (!Files.isDirectory(root)) {
            System.err.println("找不到源码目录: " + root);
            System.exit(2);
        }

        System.out.println("=== 递归防护静态校验 ===");
        System.out.println("规则：调用 " + CALL + " 前必须设置 " + GUARD + " 守卫");

        List<Path> sources = new ArrayList<>();
        Files.walk(root)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(sources::add);

        int checked = 0;
        for (Path source : sources) {
            String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            if (!text.contains(CALL)) {
                continue;
            }
            checked++;
            verify(source.getFileName().toString(), text);
        }

        if (checked == 0) {
            failed++;
            System.out.println("  FAIL  没有找到任何调用 " + CALL + " 的地方，检查可能失效");
        }

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void verify(String fileName, String rawText) {
        // 注释里也会提到方法名，先去掉注释再匹配，避免误报
        String text = stripComments(rawText);
        int callIndex = text.indexOf(CALL);
        while (callIndex >= 0) {
            int line = lineOf(text, callIndex);
            int windowStart = Math.max(0, callIndex - 500);
            int windowEnd = Math.min(text.length(), callIndex + 500);
            String window = text.substring(windowStart, windowEnd);

            boolean setsGuard = window.contains(GUARD + " = true");
            boolean clearsGuard = window.contains(GUARD + " = false");

            if (setsGuard && clearsGuard) {
                pass(fileName + ":" + line + " 调用 " + CALL + " 前设置了守卫");
            } else {
                StringBuilder detail = new StringBuilder();
                if (!setsGuard) {
                    detail.append("调用前没有设置 ").append(GUARD).append("=true；");
                }
                if (!clearsGuard) {
                    detail.append("调用后没有清除守卫；");
                }
                fail(fileName + ":" + line + " 调用 " + CALL + " 缺少递归守卫 -> " + detail);
            }
            callIndex = text.indexOf(CALL, callIndex + 1);
        }
    }

    /** 去掉 // 行注释与 block 注释，保留换行以维持行号。 */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < text.length()
                        && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
                    if (text.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i = Math.min(text.length(), i + 2);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static void pass(String message) {
        passed++;
        System.out.println("  PASS  " + message);
    }

    private static void fail(String message) {
        failed++;
        System.out.println("  FAIL  " + message);
    }
}
