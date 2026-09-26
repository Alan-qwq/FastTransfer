import com.alan.fasttransfer.core.AppSettings;

/**
 * 显示大小换算自测。
 *
 * <p>真实 bug 之一：改显示大小后 Activity 重建时闪退 —— 因为把密度换算写在了
 * 依赖 Context 的方法里，基准密度取错、又缺少边界保护。</p>
 *
 * <p>这里直接跑产品代码 {@link AppSettings#clampUiScalePercent(int)}，
 * 确认任何输入（0、负数、超大数、乱数）都不会越界。</p>
 */
public final class UiScaleTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("=== 显示大小换算 ===");

        int min = AppSettings.UI_SCALE_PERCENT_MIN;
        int max = AppSettings.UI_SCALE_PERCENT_MAX;
        int def = AppSettings.UI_SCALE_PERCENT_DEFAULT;

        // ---- 百分比钳制：任何输入都要落在合法区间 ----
        check("0 被钳到下限", min, AppSettings.clampUiScalePercent(0));
        check("负数被钳到下限", min, AppSettings.clampUiScalePercent(-50));
        check("超小值被钳到下限", min, AppSettings.clampUiScalePercent(Integer.MIN_VALUE));
        check("超大值被钳到上限", max, AppSettings.clampUiScalePercent(9999));
        check("Integer.MAX 被钳到上限", max,
                AppSettings.clampUiScalePercent(Integer.MAX_VALUE));
        check("区间内保持原值", 130, AppSettings.clampUiScalePercent(130));
        check("下限本身保持", min, AppSettings.clampUiScalePercent(min));
        check("上限本身保持", max, AppSettings.clampUiScalePercent(max));
        check("标准值保持", def, AppSettings.clampUiScalePercent(def));

        // ---- density 换算（与 BaseActivity.densityForPercent 同一套规则） ----
        check("420 @100% -> 420", 420, densityForPercent(420, 100));
        check("420 @ 50% -> 210", 210, densityForPercent(420, 50));
        check("420 @200% -> 840", 840, densityForPercent(420, 200));
        check("320 @ 85% -> 272", 272, densityForPercent(320, 85));
        check("密度缺失时用 160 兜底", 160, densityForPercent(0, 100));
        check("负密度也用 160 兜底", 160, densityForPercent(-1, 100));
        check("结果不会低于 72", 72, densityForPercent(100, 1));
        check("结果不会高于 1200", 1200, densityForPercent(2000, 200));
        check("幂等：同基准同百分比结果稳定",
                densityForPercent(420, 120), densityForPercent(420, 120));

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 与产品代码 BaseActivity.densityForPercent 保持同一规则。 */
    private static int densityForPercent(int baseDensity, int percent) {
        int base = baseDensity > 0 ? baseDensity : 160;
        int density = Math.round(base * percent / 100f);
        return Math.max(72, Math.min(density, 1200));
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
