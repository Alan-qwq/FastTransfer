import com.alan.fasttransfer.core.web.WebApproval;
import com.alan.fasttransfer.core.web.WebNames;
import com.alan.fasttransfer.core.web.WebPage;

/**
 * 网页传输的纯逻辑自测。
 *
 * <p>这一层直接对着安全边界：下载接口收的文件名、写进 HTML 的用户数据。
 * 真机上很难穷举这些输入，离线可以。</p>
 */
public final class WebPageTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("=== 网页传输 ===");

        testSafeName();
        testUrlEncode();
        testContentDisposition();
        testApiPath();
        testEscape();
        testApproval();
        testIndexPage();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 文件名 ====================

    private static void testSafeName() {
        // 目录穿越必须全部挡掉
        check("拒绝 ../", false, WebNames.isSafeName("../secret"));
        check("拒绝 a/b", false, WebNames.isSafeName("a/b"));
        check("拒绝 a\\b", false, WebNames.isSafeName("a\\b"));
        check("拒绝 ..", false, WebNames.isSafeName(".."));
        check("拒绝 .", false, WebNames.isSafeName("."));
        check("拒绝空", false, WebNames.isSafeName(""));
        check("拒绝 null", false, WebNames.isSafeName(null));
        check("拒绝绝对路径", false, WebNames.isSafeName("/etc/passwd"));
        check("拒绝换行", false, WebNames.isSafeName("a\nb"));
        check("拒绝 NUL", false, WebNames.isSafeName("a\u0000b"));
        check("拒绝超长", false, WebNames.isSafeName(repeat("x", 201)));

        // 正常文件名要放行
        check("允许普通名", true, WebNames.isSafeName("photo.jpg"));
        check("允许中文", true, WebNames.isSafeName("假期照片.jpg"));
        check("允许空格", true, WebNames.isSafeName("my file.txt"));
        check("允许点号", true, WebNames.isSafeName("archive.tar.gz"));
        check("允许长度 200", true, WebNames.isSafeName(repeat("x", 200)));
    }

    // ==================== 编码 ====================

    private static void testUrlEncode() {
        check("空格编成 %20", "my%20file.txt", WebNames.urlEncode("my file.txt"));
        check("中文被编码", "%E4%B8%AD%E6%96%87.txt", WebNames.urlEncode("中文.txt"));
        check("斜杠被编码", "a%2Fb", WebNames.urlEncode("a/b"));
        check("null 变空串", "", WebNames.urlEncode(null));
        check("安全字符不动", "abc-_.~", WebNames.urlEncode("abc-_.~"));
    }

    private static void testContentDisposition() {
        String value = WebNames.contentDisposition("中文 名.txt");
        check("含 attachment", true, value.startsWith("attachment;"));
        check("含 filename*", true, value.contains("filename*=UTF-8''"));
        check("中文进 filename*", true, value.contains(WebNames.urlEncode("中文 名.txt")));
        // 引号不能破坏头部结构
        String tricky = WebNames.contentDisposition("a\"b.txt");
        check("引号被剔除", false, tricky.substring(0, tricky.indexOf("filename*"))
                .contains("a\"b"));
    }

    // ==================== HTML 转义 ====================

    private static void testEscape() {
        check("转义 <", "&lt;script&gt;", WebPage.escape("<script>"));
        check("转义 &", "a&amp;b", WebPage.escape("a&b"));
        check("转义双引号", "&quot;x&quot;", WebPage.escape("\"x\""));
        check("转义单引号", "&#39;x&#39;", WebPage.escape("'x'"));
        check("null 变空串", "", WebPage.escape(null));
        check("中文原样", "照片.jpg", WebPage.escape("照片.jpg"));
    }

    // ==================== 路由 ====================

    /**
     * API 与页面必须分开：未授权时 API 要回 401 JSON，页面才回登录页 HTML。
     * 混在一起会让浏览器拿到 200 的 HTML、解析不出 JSON，然后静默失败。
     */
    private static void testApiPath() {
        check("prepare 是 API", true, WebNames.isApiPath("/web/prepare"));
        check("prepare 状态是 API", true, WebNames.isApiPath("/web/prepare/status"));
        check("upload 是 API", true, WebNames.isApiPath("/web/upload"));
        check("outbox 是 API", true, WebNames.isApiPath("/web/outbox"));
        check("pull 是 API", true, WebNames.isApiPath("/web/pull"));

        check("首页不是 API", false, WebNames.isApiPath("/"));
        check("web 根不是 API", false, WebNames.isApiPath("/web/"));
        check("login 不是 API", false, WebNames.isApiPath("/web/login"));
        check("未知路径不是 API", false, WebNames.isApiPath("/web/nope"));
        check("null 不是 API", false, WebNames.isApiPath(null));
    }

    // ==================== 上传凭证 ====================

    /**
     * 「电脑传手机必须手机点接收」就落在这个类上，
     * 所以这里要证明：没凭证进不去、凭证只能用一次、过期作废。
     */
    private static void testApproval() {
        WebApproval approval = new WebApproval(60_000);

        // 没经过确认的一律拒绝
        check("拒绝 null 凭证", false, approval.consume(null));
        check("拒绝空凭证", false, approval.consume(""));
        check("拒绝没发过的凭证", false, approval.consume("deadbeef"));

        // 用户点了接收：发一张，能用，但只能用一次
        String token = approval.grant();
        check("凭证非空", true, token != null && token.length() >= 16);
        check("刚发的凭证可用", true, approval.consume(token));
        check("同一个凭证不能用第二次", false, approval.consume(token));

        // 两张凭证互不影响
        String a = approval.grant();
        String b = approval.grant();
        check("两张凭证不同", false, a.equals(b));
        check("用掉 a", true, approval.consume(a));
        check("b 仍可用", true, approval.consume(b));

        // 一批多个文件：每个文件一个请求，所以配额必须够
        String batch = approval.grant(3);
        check("批次第 1 个文件", true, approval.consume(batch));
        check("批次第 2 个文件", true, approval.consume(batch));
        check("批次第 3 个文件", true, approval.consume(batch));
        check("批次第 4 个超额被拒", false, approval.consume(batch));

        // 过期的必须失效
        WebApproval shortLived = new WebApproval(1000);
        String old = shortLived.grant(1, 1_000_000L);
        check("过期凭证被拒绝", false, shortLived.consume(old, 1_000_000L + 5000));

        // 清理不该误伤还有效的
        WebApproval mixed = new WebApproval(1000);
        String stale = mixed.grant(1, 1_000_000L);
        String fresh = mixed.grant(1, 2_000_000L);
        check("清理前有 2 张", 2, mixed.pendingCount());
        mixed.purgeExpired(2_000_000L + 100);
        check("过期那张被清掉", 1, mixed.pendingCount());
        check("有效那张还在", true, mixed.consume(fresh, 2_000_000L + 200));
        check("被清掉的那张已失效", false, mixed.consume(stale, 2_000_000L + 200));
    }

    // ==================== 页面 ====================

    private static void testIndexPage() {
        String html = WebPage.index("我的手机", false, null);

        check("是 HTML", true, html.startsWith("<!DOCTYPE html>"));
        check("声明 UTF-8", true, html.contains("charset=\"utf-8\""));
        check("适配深色模式", true, html.contains("prefers-color-scheme:dark"));
        check("适配窄屏", true, html.contains("name=\"viewport\""));
        check("含设备名", true, html.contains("我的手机"));
        check("含上传表单", true, html.contains("/web/upload"));
        check("含拖放支持", true, html.contains("dataTransfer"));
        check("不联网加载资源", false, html.contains("http://cdn"));
        check("不含外部域名", false, html.contains("https://"));

        // 上传必须先在手机上确认：没有这一跳就等于没确认
        check("上传前先预检", true, html.contains("/web/prepare"));
        check("预检不阻塞而是轮询", true, html.contains("/web/prepare/status"));
        check("轮询间隔存在", true, html.contains("},500);"));
        check("上传带凭证", true, html.contains("/web/upload?token="));
        check("提示要先点接收", true, html.contains("手机上点「接收」"));
        check("被拒绝时告知用户", true, html.contains("手机上拒绝了这次传输"));
        check("超时也有提示", true, html.contains("手机一直没有确认"));
        // 必须先把 FileList 复制成数组：input.value='' 会把它清空，
        // 否则异步上传时 files[i] 是 undefined，整个流程静默断掉
        check("立刻复制 FileList", true, html.contains("var list=[]"));
        check("复制后回写 files", true, html.contains("files=list"));
        // 出问题时必须能看出卡在哪一步
        check("有诊断行", true, html.contains("id=\"diag\""));
        check("轮询显示服务端应答", true, html.contains("次查询：HTTP"));

        // 「从手机取走」已去掉，相关端点不该再出现
        check("没有下载端点", false, html.contains("/web/download"));
        check("没有列表端点", false, html.contains("/web/list"));
        check("没有取走区块", false, html.contains("从手机取走"));

        // 上传失败必须把服务器的原话显示出来，否则用户只看到「失败」两个字
        check("失败时读服务器消息", true, html.contains("j.message"));
        check("有错误详情容器", true, html.contains("class=\"detail\""));
        check("有错误详情样式", true, html.contains(".detail{"));
        // 成功时要显示存到哪了，否则用户找不到文件会以为没传成功
        check("成功时显示保存位置", true, html.contains("已保存到："));
        check("成功提示用中性样式", true, html.contains(".detail.saved{"));

        // 手机主动发：必须有待发区、轮询、以及按 token 下载的链接
        check("有待发文件区", true, html.contains("id=\"outbox-card\""));
        check("待发区在页面靠前", true,
                html.indexOf("outbox-card") < html.indexOf("传到这台手机"));
        check("有待发列表", true, html.contains("id=\"outbox\""));
        check("轮询待发列表", true, html.contains("setInterval(pollOutbox"));
        check("按 token 下载", true, html.contains("/web/pull?token="));
        check("空列表有引导", true, html.contains("点设备列表里的「电脑」"));
        // 文字消息要能直接看、直接复制，而不是逼人下载一个 txt
        check("支持文字条目", true, html.contains("f.text"));
        check("文字可复制", true, html.contains("navigator.clipboard"));
        check("复制有降级方案", true, html.contains("execCommand"));
        check("文字块样式", true, html.contains(".txt-body{"));

        // 设备名里的 HTML 必须被转义，否则就是 XSS
        String evil = WebPage.index("<img src=x onerror=alert(1)>", false, null);
        check("设备名被转义", false, evil.contains("<img src=x"));
        check("转义后的尖括号在", true, evil.contains("&lt;img"));

        // 需要 PIN 时不渲染上传区
        String locked = WebPage.index("手机", true, null);
        check("要 PIN 时给登录表单", true, locked.contains("/web/login"));
        check("要 PIN 时不渲染上传", false, locked.contains("/web/upload"));
        check("要 PIN 时不渲染脚本", false, locked.contains("XMLHttpRequest"));
    }

    // ==================== 工具 ====================

    private static String repeat(String text, int times) {
        StringBuilder sb = new StringBuilder(text.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(text);
        }
        return sb.toString();
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
