package com.alan.fasttransfer.core.web;

import android.content.Context;
import android.net.Uri;

import com.alan.fasttransfer.core.AppSettings;
import com.alan.fasttransfer.core.net.HttpServer;
import com.alan.fasttransfer.core.util.FileNames;
import com.alan.fasttransfer.core.util.FileStorage;
import com.alan.fasttransfer.core.util.Logs;
import com.alan.fasttransfer.core.util.SaveLocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 网页传输：电脑用浏览器直接和手机互传，不需要装任何东西。
 *
 * <p>复用 LocalSend 的同一个端口和同一个 HttpServer，只是多认几个 {@code /web/*}
 * 路径。这样手机侧只开一个监听端口，防火墙和权限也只需要处理一次。</p>
 *
 * <p>安全边界：</p>
 * <ul>
 *   <li>只在用户显式打开「网页传输」时才由 {@link #handle} 接手，否则这些路径一律 404；</li>
 *   <li>手机开了配对码（PIN）时，网页必须先提交正确 PIN 换一个会话 cookie；</li>
 *   <li><b>电脑传过来的每一个批次都要手机点「接收」</b>：网页先调 {@code /web/prepare}
 *       把文件清单送过来，由 {@link Approval} 弹窗询问用户，用户同意后才给一个
 *       一次性凭证，{@code /web/upload} 凭它才肯落盘；</li>
 *   <li>手机发出去的文件用不可猜的随令牌标识，{@code /web/pull} 只认令牌。</li>
 * </ul>
 */
public class WebTransfer {

    private static final String TAG = "WebTransfer";

    /**
     * 诊断日志，**release 版也输出**。
     *
     * <p>刻意不用 {@link Logs#d}：它在 release 下是静默的，而网页传输出问题时
     * 用户装的多半是 release 包，用 Logs.d 等于什么都没记（踩过一次）。
     * 排查这类「点了没反应」的问题，只靠 debug 包的日志是不够的。</p>
     */
    private static void log(String message) {
        android.util.Log.i(TAG, message);
    }

    /** 会话 cookie 名。 */
    private static final String COOKIE = "ft_web";
    /** 未开 PIN 时用的固定会话值，省得每次请求都新建。 */
    private static final String OPEN_SESSION = "open";

    private final Context context;
    private final AppSettings settings;
    private final FileStorage storage;

    /** 当前有效的网页会话 token（进程内有效，重启即失效）。 */
    private volatile String sessionToken = OPEN_SESSION;

    /**
     * 上传前的确认钩子，由 {@code ReceiveManager} 注入。
     *
     * <p>没有它时一律<b>拒绝</b>：网页上传必须经过手机用户点头，
     * 这是「电脑传手机也要手机点接收」这条要求的落点。</p>
     */
    private volatile Approval approval;

    /** 已批准、等待正文的上传凭证。 */
    private final WebApproval approvals = new WebApproval();

    /** 一次「等用户确认」的上传批次。 */
    private static final class Preparation {
        final List<String> names;
        final long createdAt = System.currentTimeMillis();
        volatile boolean done;
        volatile boolean accepted;
        volatile String token;

        Preparation(List<String> names) {
            this.names = names;
        }
    }

    /** 待用户确认的上传批次；session -> 状态。 */
    private final Map<String, Preparation> preparations =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 一个批次最多挂这么久，超时就当作过期。 */
    private static final long PREPARE_TTL_MS = 5 * 60 * 1000L;

    /** 把「有电脑想传文件过来」交给手机用户确认。 */
    public interface Approval {
        /**
         * @param clientIp 发起方 IP，用于弹窗里显示来源
         * @param names    文件名列表
         * @param sizes    对应的大小（可能含 0 表示未知）
         * @return true 表示用户接受
         */
        boolean approveUpload(String clientIp, List<String> names, List<Long> sizes);
    }

    public void setApproval(Approval value) {
        this.approval = value;
    }

    /**
     * 网页上传完成后的回调。
     *
     * <p>网页上传不走 LocalSend 的会话，也就不会触发 {@code onSessionFinished}，
     * 所以传输记录里一直看不到它。用这个回调把它补进历史。</p>
     */
    public interface UploadListener {
        void onWebUploadFinished(String clientIp, List<String> names, long totalBytes,
                                 List<String> savedUris, String firstMime, String firstPath);
    }

    private volatile UploadListener uploadListener;

    public void setUploadListener(UploadListener value) {
        this.uploadListener = value;
    }

    public WebTransfer(Context context, AppSettings settings, FileStorage storage) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.storage = storage;
        // 进程重启后把上次没发完的队列捞回来
        WebOutbox.restore(this.context);
        refreshSession();
    }

    /** PIN 变化或功能重开时调用，让旧 cookie 立刻失效。 */
    public void refreshSession() {
        String pin = settings.effectivePin();
        if (pin == null) {
            sessionToken = OPEN_SESSION;
        } else {
            // 复用凭证那套随机数，避免两处各写一份
            sessionToken = WebApproval.newToken();
        }
    }

    /** 这个路径是不是网页传输的。 */
    public static boolean owns(String path) {
        return "/".equals(path) || path.startsWith("/web/");
    }

    /**
     * 处理一个网页请求。
     *
     * @return 响应；返回 null 表示「不该由网页传输管」，交给 LocalSend 路由继续判断
     */
    public HttpServer.Response handle(HttpServer.Request request) throws IOException {
        if (!settings.isWebEnabled()) {
            return null;
        }
        String path = request.path;
        if (!owns(path)) {
            return null;
        }

        if ("/web/login".equals(path)) {
            return onLogin(request);
        }
        if (!authorized(request)) {
            // API 和页面必须分开处理：API 回 401 JSON，页面才回登录页 HTML。
            // 早先这里一律回登录页（状态码 200），浏览器拿到 200 却解析不出 JSON，
            // 于是「点了接收也传不过去」，而且网页上什么错都看不到。
            if (WebNames.isApiPath(path)) {
                return jsonError(401, "配对码已失效，请刷新页面重新输入");
            }
            return page(null, true, null);
        }
        if ("/web/prepare".equals(path)) {
            return onPrepare(request);
        }
        if ("/web/prepare/status".equals(path)) {
            return onPrepareStatus(request);
        }
        if ("/web/upload".equals(path)) {
            return onUpload(request);
        }

        if ("/".equals(path) || "/web/".equals(path) || "/web".equals(path)) {
            return page(null, false, null);
        }
        if ("/web/outbox".equals(path)) {
            return onOutbox();
        }
        if ("/web/pull".equals(path)) {
            return onPull(request);
        }
        return HttpServer.Response.message(404, "Not found");
    }

    // ==================== 认证 ====================

    private boolean authorized(HttpServer.Request request) {
        String pin = settings.effectivePin();
        if (pin == null) {
            return true;
        }
        String cookie = request.header("Cookie");
        if (cookie == null) {
            return false;
        }
        String expected = COOKIE + "=" + sessionToken;
        // cookie 里可能还有别的项，逐个比对
        for (String part : cookie.split(";")) {
            if (part.trim().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private HttpServer.Response onLogin(HttpServer.Request request) throws IOException {
        String pin = settings.effectivePin();
        if (pin == null) {
            return redirect("/");
        }
        byte[] body = request.readBody();
        String submitted = HttpServer.parseQuery(new String(body, "UTF-8")).get("pin");
        if (submitted != null && submitted.trim().equals(pin)) {
            refreshSession();
            HttpServer.Response response = redirect("/");
            response.header("Set-Cookie",
                    COOKIE + "=" + sessionToken + "; Path=/; HttpOnly; SameSite=Lax");
            return response;
        }
        Logs.w(TAG, "web login failed from " + request.clientIp);
        return page("配对码不正确，请重新输入。", true, null);
    }

    private static HttpServer.Response redirect(String location) {
        HttpServer.Response response = HttpServer.Response.empty(302);
        response.header("Location", location);
        return response;
    }

    private HttpServer.Response page(String notice, boolean pinRequired, String extra) {
        String html = WebPage.index(settings.getAlias(), pinRequired,
                notice == null ? extra : notice);
        return htmlResponse(html);
    }

    private static HttpServer.Response htmlResponse(String html) {
        HttpServer.Response response = new HttpServer.Response();
        response.status = 200;
        response.contentType = "text/html; charset=utf-8";
        try {
            response.body = html.getBytes("UTF-8");
        } catch (Exception e) {
            response.body = new byte[0];
        }
        response.header("Cache-Control", "no-store");
        return response;
    }

    // ==================== 上传（需手机确认） ====================

    /**
     * 上传预检第一步：把文件清单送来，请手机用户确认。
     *
     * <p><b>立刻返回</b>一个 session，不在这里等用户。等待放到后台线程去做，
     * 浏览器改为轮询 {@code /web/prepare/status}。这样做的好处：</p>
     * <ul>
     *   <li>不占着 HTTP 工作线程，也不让浏览器长时间挂着一个请求
     *       （有些网络栈和中间层会掐断挂起的连接，表现成「点了接收没反应」）；</li>
     *   <li>每一步状态都能看见：页面能明确区分「等待确认」「已拒绝」「超时」。</li>
     * </ul>
     *
     * <p>请求体是一行 JSON：{@code {"files":[{"name":"a.jpg","size":123}, ...]}}</p>
     */
    private HttpServer.Response onPrepare(HttpServer.Request request) throws IOException {
        byte[] body = request.readBody();
        final List<String> names = new ArrayList<>();
        final List<Long> sizes = new ArrayList<>();
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser
                    .parseString(new String(body, "UTF-8")).getAsJsonObject();
            com.google.gson.JsonArray array = root.getAsJsonArray("files");
            if (array != null) {
                for (int i = 0; i < array.size(); i++) {
                    com.google.gson.JsonObject item = array.get(i).getAsJsonObject();
                    String name = item.has("name") ? item.get("name").getAsString() : "";
                    if (name.isEmpty()) {
                        continue;
                    }
                    names.add(name);
                    sizes.add(item.has("size") ? item.get("size").getAsLong() : 0L);
                }
            }
        } catch (Throwable t) {
            return jsonError(400, "请求格式不对");
        }
        if (names.isEmpty()) {
            return jsonError(400, "没有收到文件");
        }
        log("prepare: " + names.size() + " file(s) from " + request.clientIp
                + ", first=" + names.get(0));

        final Approval check = approval;
        if (check == null) {
            Logs.w(TAG, "no approval hook installed; rejecting web upload");
            return jsonError(403, "手机上拒绝了这次传输");
        }

        purgePreparations();
        String session = WebApproval.newToken();
        final Preparation preparation = new Preparation(names);
        preparations.put(session, preparation);

        final String clientIp = request.clientIp;
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean accepted = false;
                try {
                    accepted = check.approveUpload(clientIp, names, sizes);
                } catch (Throwable t) {
                    Logs.w(TAG, "approval failed: " + t);
                }
                log("prepare decision: " + accepted);
                if (accepted) {
                    preparation.token = approvals.grant(names.size());
                }
                preparation.accepted = accepted;
                preparation.done = true;
            }
        }, "web-approval").start();

        return HttpServer.Response.json(200, "{\"session\":\"" + session + "\"}");
    }

    /** 轮询用户有没有点确认。 */
    private HttpServer.Response onPrepareStatus(HttpServer.Request request) {
        purgePreparations();
        String session = request.query("session");
        Preparation preparation = session == null ? null : preparations.get(session);
        if (preparation == null) {
            log("status poll: unknown session");
            return jsonError(404, "这次请求已过期，请重新选择文件");
        }
        if (!preparation.done) {
            return HttpServer.Response.json(200, "{\"state\":\"pending\"}");
        }
        preparations.remove(session);
        if (!preparation.accepted) {
            log("status poll: denied");
            return HttpServer.Response.json(200, "{\"state\":\"denied\"}");
        }
        log("status poll: accepted, handing out upload token");
        return HttpServer.Response.json(200,
                "{\"state\":\"accepted\",\"token\":\"" + preparation.token + "\"}");
    }

    private void purgeExpiredApprovals() {
        approvals.purgeExpired();
    }

    private void purgePreparations() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Preparation> entry : preparations.entrySet()) {
            Preparation preparation = entry.getValue();
            if (preparation == null || now - preparation.createdAt > PREPARE_TTL_MS) {
                preparations.remove(entry.getKey());
            }
        }
    }

    private HttpServer.Response onUpload(HttpServer.Request request) throws IOException {
        // 必须先通过 /web/prepare 拿到用户点头，否则一律拒绝。
        // 这一步不能省：否则有人可以绕过确认页直接 POST 上来。
        String token = request.query("token");
        if (!approvals.consume(token)) {
            Logs.w(TAG, "web upload rejected: no valid approval (token="
                    + (token == null ? "null" : "present") + ") from " + request.clientIp);
            return jsonError(403, "这次传输还没有在手机上确认");
        }

        String contentType = request.header("Content-Type");
        String boundary = WebMultipart.boundaryOf(contentType);
        if (boundary == null) {
            Logs.w(TAG, "web upload rejected: no boundary in '" + contentType + "'");
            return HttpServer.Response.message(400, "缺少 multipart boundary");
        }
        log("web upload start: " + request.contentLength() + " bytes");

        final Uri tree = SaveLocation.currentTreeUri(settings);
        final boolean nest = SaveLocation.isManualPath(settings);
        // 把每个文件的失败原因攒起来原样回给网页。
        // 否则 handler 一抛异常就被 HttpServer 兜成通用的 "Internal error"，
        // 用户只看到「失败」两个字，完全没法判断是权限、目录还是文件名的问题。
        final List<String> errors = new ArrayList<>();
        // 成功保存到哪也要回给网页：文件其实存好了但用户找不到时，
        // 光看「完成」是没法判断的，把路径亮出来最省事。
        final List<String> saved = new ArrayList<>();
        // 同一批文件的这些信息要交给历史记录
        final List<String> savedNames = new ArrayList<>();
        final List<String> savedUris = new ArrayList<>();
        final String[] firstMime = {""};

        WebMultipart.PartHandler handler = new WebMultipart.PartHandler() {
            private FileStorage.Target target;
            private String savedName = "";

            @Override
            public OutputStream onPartStart(WebMultipart.Part part) throws IOException {
                if (!part.hasFile()) {
                    return null;
                }
                savedName = FileNames.sanitize(part.fileName);
                try {
                    target = storage.openTarget(savedName, part.contentType, tree, nest);
                } catch (Throwable t) {
                    Logs.w(TAG, "openTarget failed for " + savedName + ": " + t);
                    errors.add(savedName + "：" + describe(t));
                    target = null;
                    return null;
                }
                return target.stream;
            }

            @Override
            public void onPartEnd(WebMultipart.Part part, boolean success) {
                if (target == null) {
                    return;
                }
                if (success) {
                    try {
                        storage.publish(target);
                        log("web upload saved: " + savedName
                                + " -> " + target.displayPath);
                        if (target.displayPath != null && !target.displayPath.isEmpty()) {
                            saved.add(target.displayPath);
                        }
                        savedNames.add(savedName);
                        if (target.openUri != null) {
                            savedUris.add(target.openUri.toString());
                        }
                        if (firstMime[0].isEmpty() && part.contentType != null) {
                            firstMime[0] = part.contentType;
                        }
                    } catch (Throwable t) {
                        Logs.w(TAG, "publish failed for " + savedName + ": " + t);
                        errors.add(savedName + "：" + describe(t));
                    }
                } else {
                    storage.abandon(target);
                    errors.add(savedName + "：写入中断");
                }
                target = null;
            }
        };

        WebMultipart.Result result = WebMultipart.parse(request.body, boundary, handler);

        if (!errors.isEmpty()) {
            return jsonError(500, errors.get(0));
        }
        if (result.fileCount == 0) {
            return jsonError(400, "没有收到文件");
        }
        StringBuilder json = new StringBuilder(128);
        json.append("{\"ok\":true,\"count\":").append(result.fileCount)
                .append(",\"bytes\":").append(result.totalBytes)
                .append(",\"saved\":[");
        for (int i = 0; i < saved.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(jsonEscape(saved.get(i))).append('"');
        }
        json.append("]}");
        log("web upload done: " + result.fileCount + " file(s), "
                + result.totalBytes + " bytes");

        // 补一条传输记录：网页上传不走 LocalSend 会话，否则历史里看不到它
        UploadListener listener = uploadListener;
        if (listener != null) {
            try {
                listener.onWebUploadFinished(request.clientIp, savedNames, result.totalBytes,
                        savedUris, firstMime[0], saved.isEmpty() ? "" : saved.get(0));
            } catch (Throwable t) {
                Logs.w(TAG, "history callback failed: " + t);
            }
        }
        return HttpServer.Response.json(200, json.toString());
    }

    /** 把异常转成用户能看懂的一句话。 */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isEmpty()) {
            message = t.getClass().getSimpleName();
        }
        if (t instanceof SecurityException
                || message.contains("Permission")
                || message.contains("EPERM")
                || message.contains("EACCES")) {
            return "没有写入权限，请在手机设置里换一个保存位置（" + message + "）";
        }
        return message;
    }

    private static HttpServer.Response jsonError(int status, String message) {
        return HttpServer.Response.json(status,
                "{\"message\":\"" + jsonEscape(message) + "\"}");
    }

    // ==================== 手机主动发（待发队列） ====================

    /** 网页轮询这个接口，拿到「手机准备发给你的文件」。 */
    private HttpServer.Response onOutbox() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"files\":[");
        boolean first = true;
        for (WebOutbox.Item item : WebOutbox.list()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"token\":\"").append(jsonEscape(item.token))
                    .append("\",\"name\":\"").append(jsonEscape(item.name))
                    .append("\",\"size\":").append(item.size)
                    .append(",\"time\":").append(item.time)
                    .append(",\"text\":").append(item.isText())
                    .append(",\"mime\":\"").append(jsonEscape(item.mime));
            if (item.isText()) {
                // 文字直接内联给网页，省一次请求；长度做个上限免得一屏塞爆
                String body = item.text.length() > 20000
                        ? item.text.substring(0, 20000) : item.text;
                sb.append("\",\"body\":\"").append(jsonEscape(body));
            }
            sb.append("\"}");
        }
        sb.append("]}");
        HttpServer.Response response = HttpServer.Response.json(200, sb.toString());
        response.header("Cache-Control", "no-store");
        return response;
    }

    /**
     * 按令牌把手机上的文件流给浏览器。
     *
     * <p>这里读的是原始 {@code content://} Uri —— App 自己持有读权限，
     * 不需要先把文件复制到任何目录。</p>
     */
    private HttpServer.Response onPull(HttpServer.Request request) throws IOException {
        String token = request.query("token");
        final WebOutbox.Item item = WebOutbox.find(token);
        if (item == null) {
            return HttpServer.Response.message(404, "文件已不在待发列表里");
        }

        // 文字条目不是文件，直接当文本回；网页也能点开看
        if (item.isText()) {
            HttpServer.Response response = new HttpServer.Response();
            response.status = 200;
            response.contentType = "text/plain; charset=utf-8";
            try {
                response.body = item.text.getBytes("UTF-8");
            } catch (Exception e) {
                response.body = new byte[0];
            }
            response.header("Content-Disposition", WebNames.contentDisposition(item.name));
            return response;
        }

        final Uri uri = Uri.parse(item.uri);
        HttpServer.StreamWriter writer = new HttpServer.StreamWriter() {
            @Override
            public void writeTo(OutputStream out) throws IOException {
                InputStream in = null;
                try {
                    in = context.getContentResolver().openInputStream(uri);
                    if (in == null) {
                        throw new IOException("无法读取该文件");
                    }
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        };

        HttpServer.Response response = item.size > 0
                ? HttpServer.Response.stream(200, item.mime, item.size, writer)
                : HttpServer.Response.stream(200, item.mime, writer);
        response.header("Content-Disposition", WebNames.contentDisposition(item.name));
        log("web pull: " + item.name);
        return response;
    }

    // ==================== 小工具 ====================

    private static String urlEncode(String text) {
        return WebNames.urlEncode(text);
    }

    private static String jsonEscape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
