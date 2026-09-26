import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Context 包装防护校验。
 *
 * <p>背景：为了做「显示大小」，一度在 {@code attachBaseContext} 里用
 * {@code createConfigurationContext()} 返回了一个 ContextWrapper。那个对象
 * <b>不是 Activity</b>，于是 Fragment 的 {@code onAttach(Context)} 拿到它之后
 * {@code getActivity()} 返回 null，Fragment 里的字段全空，Activity 重建时直接 NPE
 * （已真实发生）。</p>
 *
 * <p>规则：把 Context 交给别人的方法，必须在包装前判断
 * {@code context instanceof Activity}，是 Activity 就原样返回；
 * 同时 Activity 自身要用 {@code applyOverrideConfiguration} 改配置，
 * 而不是造一个新的 Context。</p>
 *
 * 用法：java ContextWrapperCheck &lt;源码根目录&gt;
 */
public final class ContextWrapperCheck {

    private static final String APPLY_UI_SCALE = "applyUiScale";
    private static final String ACTIVITY_GUARD = "instanceof Activity";
    private static final String CREATE_CONFIG_CONTEXT = "createConfigurationContext";
    private static final String OVERRIDE_CONFIG = "applyOverrideConfiguration";

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: ContextWrapperCheck <java source root>");
            System.exit(2);
        }
        Path root = Paths.get(args[0]);
        if (!Files.isDirectory(root)) {
            System.err.println("找不到源码目录: " + root);
            System.exit(2);
        }

        System.out.println("=== Context 包装防护校验 ===");
        System.out.println("规则：包装 Context 前必须判断 instanceof Activity");
        System.out.println();

        List<Path> sources = new ArrayList<>();
        Files.walk(root)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(sources::add);

        boolean checkedAny = false;
        for (Path source : sources) {
            String text = stripComments(new String(Files.readAllBytes(source),
                    StandardCharsets.UTF_8));
            if (!text.contains(APPLY_UI_SCALE)) {
                continue;
            }
            checkedAny = true;
            verifyApplyUiScale(source.getFileName().toString(), text);
        }
        if (!checkedAny) {
            fail("没有找到 " + APPLY_UI_SCALE + " 的实现，检查可能失效");
        }

        // BaseActivity 必须用 applyOverrideConfiguration
        for (Path source : sources) {
            if (!source.getFileName().toString().equals("BaseActivity.java")) {
                continue;
            }
            String text = stripComments(new String(Files.readAllBytes(source),
                    StandardCharsets.UTF_8));
            if (text.contains(OVERRIDE_CONFIG)) {
                pass("BaseActivity 使用 applyOverrideConfiguration 改配置");
            } else {
                fail("BaseActivity 没有使用 " + OVERRIDE_CONFIG
                        + " —— 改 configure 不应造新 Context");
            }
        }

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void verifyApplyUiScale(String fileName, String text) {
        int index = text.indexOf("static Context " + APPLY_UI_SCALE);
        if (index < 0) {
            index = text.indexOf(APPLY_UI_SCALE + "(Context");
        }
        if (index < 0) {
            return;
        }
        int end = Math.min(text.length(), index + 900);
        String body = text.substring(index, end);

        boolean guardsActivity = body.contains(ACTIVITY_GUARD);
        boolean wraps = body.contains(CREATE_CONFIG_CONTEXT);

        if (guardsActivity) {
            pass(fileName + " 的 " + APPLY_UI_SCALE + " 在包装前判断了 instanceof Activity");
        } else if (wraps) {
            fail(fileName + " 的 " + APPLY_UI_SCALE + " 会包装 Context，"
                    + "但没有判断 instanceof Activity —— Fragment 的 getActivity() 会返回 null");
        } else {
            pass(fileName + " 的 " + APPLY_UI_SCALE + " 不做 Context 包装");
        }
    }

    /** 去掉注释，避免注释里提到关键字造成误判。 */
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

    private static void pass(String message) {
        passed++;
        System.out.println("  PASS  " + message);
    }

    private static void fail(String message) {
        failed++;
        System.out.println("  FAIL  " + message);
    }
}
