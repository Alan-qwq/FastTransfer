package com.alan.fasttransfer.core;

import android.os.Handler;
import android.os.Looper;

import com.alan.fasttransfer.core.net.HttpServer;
import com.alan.fasttransfer.core.net.MulticastDiscovery;
import com.alan.fasttransfer.core.net.HttpUtil;
import com.alan.fasttransfer.core.net.HttpResult;
import com.alan.fasttransfer.core.dto.DeviceInfoDto;
import com.alan.fasttransfer.core.dto.FileDto;
import com.alan.fasttransfer.core.dto.PrepareUploadRequestDto;
import com.alan.fasttransfer.core.dto.PrepareUploadResponseDto;
import com.alan.fasttransfer.core.transfer.Peer;
import com.alan.fasttransfer.core.transfer.PeerInfo;
import com.alan.fasttransfer.core.transfer.SendItem;
import com.alan.fasttransfer.core.transfer.TransferFile;
import com.alan.fasttransfer.core.transfer.TransferSession;
import com.alan.fasttransfer.core.util.Json;
import com.alan.fasttransfer.core.util.Logs;
import com.alan.fasttransfer.core.util.Streams;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 接收侧：LocalSend v2.2 HTTP 服务端 + 收发会话管理。
 */
public class ReceiveManager implements HttpServer.Handler {

    private static final String TAG = "ReceiveManager";

    /**
     * 诊断日志，**release 版也输出**。
     *
     * <p>不用 {@link Logs#d}：它在 release 下静默，而用户报「点了没反应」时
     * 装的多半正是 release 包，那样等于没记日志（已经因此白跑过一轮）。</p>
     */
    private static void log(String message) {
        android.util.Log.i(TAG, message);
    }

    /** 待用户确认的请求。 */
    public static class PendingRequest {
        public String sessionId;
        public String senderIp;
        public PeerInfo sender;
        public final List<TransferFile> files = new ArrayList<>();
        public final Map<String, FileDto> dtos = new HashMap<>();
        public final Object lock = new Object();
        public boolean answered;
        public boolean accepted;
        public final List<String> acceptedIds = new ArrayList<>();
        public long createdAt = System.currentTimeMillis();
    }

    /** 用户对一次请求的答复。 */
    public static class Decision {
        public boolean accepted;
        /** null / 空表示全部接受。 */
        public List<String> acceptedIds;
    }

    /** 接收进度回调。 */
    public interface Listener {
        void onIncomingRequest(PendingRequest request);

        void onRequestCancelled(String sessionId);

        void onSessionUpdated(TransferSession session);

        void onSessionFinished(TransferSession session);

        void onTextReceived(PeerInfo from, String text, String savedPath);
    }

    private static class SessionFile {
        FileDto dto;
        String token;
        int status = TransferFile.STATUS_PENDING;
    }

    private static class ActiveSession {
        String sessionId;
        String senderIp;
        PeerInfo sender;
        final Map<String, SessionFile> files = new HashMap<>();
        /** 保护 files 的并发上传。 */
        final Object fileLock = new Object();
        TransferSession uiSession;
    }

    private final AppSettings settings;
    private final HttpServer server = new HttpServer(this);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> pinAttempts = new HashMap<>();
    private final com.alan.fasttransfer.core.util.FileStorage storage;
    /** 网页传输（电脑用浏览器互传）；默认关闭，见 AppSettings#isWebEnabled。 */
    private final com.alan.fasttransfer.core.web.WebTransfer web;

    private PendingRequest pending;
    private ActiveSession active;
    private volatile boolean running;

    public ReceiveManager(AppSettings settings, com.alan.fasttransfer.core.util.FileStorage storage) {
        this.settings = settings;
        this.storage = storage;
        this.web = new com.alan.fasttransfer.core.web.WebTransfer(
                settings.getContext(), settings, storage);
        // 网页上传也必须经过手机用户点头，复用同一套确认弹窗
        this.web.setApproval(new com.alan.fasttransfer.core.web.WebTransfer.Approval() {
            @Override
            public boolean approveUpload(String clientIp, java.util.List<String> names,
                                         java.util.List<Long> sizes) {
                return requestWebUpload(clientIp, names, sizes);
            }
        });
    }

    /**
     * 把「有电脑想传文件过来」变成一次和 LocalSend 完全一样的确认。
     *
     * <p>复用 {@link PendingRequest} + {@link #notifyIncomingRequest} +
     * {@link #awaitDecision} 这条链路，所以手机端弹的窗、超时行为、
     * 拒绝的处理都和局域网传输一致，不需要另写一套 UI。</p>
     *
     * @return 用户是否接受；超时或已有其它会话时返回 false
     */
    private boolean requestWebUpload(String clientIp, java.util.List<String> names,
                                     java.util.List<Long> sizes) {
        PendingRequest request = new PendingRequest();
        request.sessionId = java.util.UUID.randomUUID().toString();
        request.senderIp = clientIp;
        request.sender = webSender(clientIp);

        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            long size = i < sizes.size() && sizes.get(i) != null ? sizes.get(i) : 0;
            String id = "web-" + i;
            FileDto dto = new FileDto(id, name, size, null);
            request.dtos.put(id, dto);
            request.files.add(TransferFile.fromDto(dto));
        }
        if (request.files.isEmpty()) {
            Logs.w(TAG, "web request has no files");
            return false;
        }

        synchronized (this) {
            if (pending != null || active != null) {
                Logs.w(TAG, "web request refused: busy (pending=" + (pending != null)
                        + " active=" + (active != null) + ")");
                return false;
            }
            pending = request;
        }

        log("web request asking user: session=" + request.sessionId
                + " files=" + request.files.size());
        notifyIncomingRequest(request);

        Decision decision = awaitDecision(request);
        clearPending(request);
        log("web request decision: "
                + (decision == null ? "TIMEOUT" : (decision.accepted ? "accepted" : "declined")));
        return decision != null && decision.accepted;
    }

    /** 造一个代表「电脑浏览器」的发送方信息，用于弹窗显示。 */
    private PeerInfo webSender(String clientIp) {
        PeerInfo sender = new PeerInfo();
        sender.alias = "电脑浏览器";
        sender.ip = clientIp == null ? "" : clientIp;
        sender.port = 0;
        sender.deviceType = LocalsendProtocol.DEVICE_TYPE_DESKTOP;
        return sender;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isRunning() {
        return running;
    }

    public int port() {
        return server.getPort() > 0 ? server.getPort() : settings.getPort();
    }

    /**
     * 设置里的 PIN 或「网页传输」开关变动后调用。
     *
     * <p>换了 PIN 就必须让已经发出去的网页会话 cookie 立刻失效，
     * 否则改了配对码旧会话照样能传文件。</p>
     */
    public void onWebSettingsChanged() {
        web.refreshSession();
    }

    /** 让上层（TransferEngine）接住「网页上传完成」，好写进传输记录。 */
    public void setWebUploadListener(com.alan.fasttransfer.core.web.WebTransfer.UploadListener value) {
        web.setUploadListener(value);
    }

    public PendingRequest currentPending() {
        synchronized (this) {
            return pending;
        }
    }

    public TransferSession currentSession() {
        synchronized (this) {
            return active == null ? null : active.uiSession;
        }
    }

    public synchronized boolean start() {
        if (running) {
            return true;
        }
        int port = settings.getPort();
        if (server.start(port)) {
            running = true;
            Logs.d(TAG, "server started on " + port);
            return true;
        }
        // 端口被占用时退回默认端口
        if (port != LocalsendProtocol.DEFAULT_PORT && server.start(LocalsendProtocol.DEFAULT_PORT)) {
            settings.setPort(LocalsendProtocol.DEFAULT_PORT);
            running = true;
            Logs.w(TAG, "fallback to default port " + LocalsendProtocol.DEFAULT_PORT);
            return true;
        }
        running = false;
        return false;
    }

    public synchronized void stop() {
        running = false;
        server.stop();
        synchronized (this) {
            if (pending != null) {
                synchronized (pending.lock) {
                    pending.answered = true;
                    pending.accepted = false;
                    pending.lock.notifyAll();
                }
                pending = null;
            }
            active = null;
        }
    }

    // ==================== HTTP 处理 ====================

    @Override
    public HttpServer.Response handle(HttpServer.Request request) throws IOException {
        String path = request.path;
        Logs.d(TAG, request.method + " " + path);
        // 网页传输优先：它只认 / 和 /web/*，且功能关闭时一律返回 null 交回下面
        HttpServer.Response webResponse = web.handle(request);
        if (webResponse != null) {
            return webResponse;
        }
        if (LocalsendProtocol.PATH_REGISTER.equals(path)) {
            return onRegister(request);
        }
        if (LocalsendProtocol.PATH_INFO.equals(path)) {
            return onInfo();
        }
        if (LocalsendProtocol.PATH_PREPARE_UPLOAD.equals(path)) {
            return onPrepareUpload(request);
        }
        if (LocalsendProtocol.PATH_UPLOAD.equals(path)) {
            return onUpload(request);
        }
        if (LocalsendProtocol.PATH_CANCEL.equals(path)) {
            return onCancel(request);
        }
        return HttpServer.Response.message(404, "Not found");
    }

    private HttpServer.Response onRegister(HttpServer.Request request) throws IOException {
        byte[] body = request.readBody();
        DeviceInfoDto info = Json.fromJson(new String(body, "UTF-8"), DeviceInfoDto.class);
        if (info != null) {
            info.deviceType = LocalsendProtocol.normalizeDeviceType(info.deviceType);
        }
        return HttpServer.Response.json(200, Json.toJson(localInfo()));
    }

    private HttpServer.Response onInfo() {
        return HttpServer.Response.json(200, Json.toJson(localInfo()));
    }

    private DeviceInfoDto localInfo() {
        return DeviceInfoDto.of(settings.getAlias(), null, LocalsendProtocol.DEVICE_TYPE_MOBILE,
                settings.getFingerprint(), port());
    }

    private HttpServer.Response onPrepareUpload(HttpServer.Request request) throws IOException {
        Map<String, String> query = request.query();

        // PIN 校验
        String requiredPin = settings.effectivePin();
        if (requiredPin != null) {
            String provided = query.get("pin");
            if (provided == null) {
                return HttpServer.Response.message(401, "PIN required");
            }
            if (!requiredPin.equals(provided)) {
                int count;
                synchronized (pinAttempts) {
                    Integer current = pinAttempts.get(request.clientIp);
                    count = current == null ? 0 : current;
                    pinAttempts.put(request.clientIp, count + 1);
                }
                if (count + 1 >= LocalsendProtocol.MAX_PIN_ATTEMPTS) {
                    return HttpServer.Response.message(429, "Too many requests");
                }
                return HttpServer.Response.message(401, "Invalid PIN");
            }
            synchronized (pinAttempts) {
                pinAttempts.remove(request.clientIp);
            }
        }

        byte[] body = request.readBody();
        PrepareUploadRequestDto payload = Json.fromJson(new String(body, "UTF-8"),
                PrepareUploadRequestDto.class);
        if (payload == null || payload.files == null || payload.files.isEmpty()) {
            return HttpServer.Response.message(400, "No files provided");
        }

        PendingRequest request2 = new PendingRequest();
        request2.sessionId = java.util.UUID.randomUUID().toString();
        request2.senderIp = request.clientIp;
        request2.sender = PeerInfo.from(payload.info == null ? new DeviceInfoDto() : payload.info,
                request.clientIp);
        if (request2.sender.port <= 0) {
            request2.sender.port = LocalsendProtocol.DEFAULT_PORT;
        }
        for (Map.Entry<String, FileDto> entry : payload.files.entrySet()) {
            FileDto dto = entry.getValue();
            if (dto == null) {
                continue;
            }
            if (dto.id == null || dto.id.isEmpty()) {
                dto.id = entry.getKey();
            }
            request2.dtos.put(dto.id, dto);
            request2.files.add(TransferFile.fromDto(dto));
        }

        synchronized (this) {
            if (pending != null || active != null) {
                return HttpServer.Response.message(409, "Blocked by another session");
            }
            pending = request2;
        }

        notifyIncomingRequest(request2);

        Decision decision = awaitDecision(request2);
        if (decision == null) {
            clearPending(request2);
            return HttpServer.Response.message(403, "Cancelled by sender");
        }
        if (!decision.accepted) {
            clearPending(request2);
            return HttpServer.Response.message(403, "Rejected");
        }

        List<String> acceptedIds = decision.acceptedIds;
        if (acceptedIds == null || acceptedIds.isEmpty()) {
            acceptedIds = new ArrayList<>(request2.dtos.keySet());
        }

        ActiveSession session = new ActiveSession();
        session.sessionId = request2.sessionId;
        session.senderIp = request.clientIp;
        session.sender = request2.sender;

        PrepareUploadResponseDto response = new PrepareUploadResponseDto();
        response.sessionId = request2.sessionId;

        TransferSession uiSession = new TransferSession();
        uiSession.id = request2.sessionId;
        uiSession.direction = TransferSession.DIRECTION_RECEIVE;
        uiSession.peer = request2.sender;
        uiSession.state = TransferSession.STATE_RUNNING;
        session.uiSession = uiSession;

        for (String id : acceptedIds) {
            FileDto dto = request2.dtos.get(id);
            if (dto == null) {
                continue;
            }
            SessionFile file = new SessionFile();
            file.dto = dto;
            file.token = java.util.UUID.randomUUID().toString();
            session.files.put(id, file);
            response.files.put(id, file.token);
            uiSession.files.add(TransferFile.fromDto(dto));
        }

        if (session.files.isEmpty()) {
            clearPending(request2);
            return HttpServer.Response.empty(204);
        }

        synchronized (this) {
            pending = null;
            active = session;
        }

        notifySessionUpdated(uiSession);
        return HttpServer.Response.json(200, Json.toJson(response));
    }

    private Decision awaitDecision(PendingRequest request) {
        long deadline = System.currentTimeMillis() + LocalsendProtocol.PREPARE_DECISION_TIMEOUT_MS;
        synchronized (request.lock) {
            while (!request.answered) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }
                try {
                    request.lock.wait(Math.min(remaining, 1000L));
                } catch (InterruptedException e) {
                    return null;
                }
            }
        }
        Decision decision = new Decision();
        decision.accepted = request.accepted;
        decision.acceptedIds = new ArrayList<>(request.acceptedIds);
        return decision;
    }

    /** 用户接受/拒绝；acceptedIds 为空表示全部接受。 */
    public boolean respondToRequest(String sessionId, boolean accepted, List<String> acceptedIds) {
        PendingRequest request;
        synchronized (this) {
            request = pending;
        }
        if (request == null) {
            Logs.w(TAG, "respond ignored: no pending request (session=" + sessionId + ")");
            return false;
        }
        if (!request.sessionId.equals(sessionId)) {
            Logs.w(TAG, "respond ignored: session mismatch (pending=" + request.sessionId
                    + " answered=" + sessionId + ")");
            return false;
        }
        synchronized (request.lock) {
            if (request.answered) {
                Logs.w(TAG, "respond ignored: already answered");
                return false;
            }
            request.answered = true;
            request.accepted = accepted;
            request.acceptedIds.clear();
            if (accepted && acceptedIds != null) {
                request.acceptedIds.addAll(acceptedIds);
            }
            request.lock.notifyAll();
        }
        Logs.d(TAG, "respond delivered: session=" + sessionId + " accepted=" + accepted);
        return true;
    }

    private void clearPending(PendingRequest request) {
        synchronized (this) {
            if (pending == request) {
                pending = null;
            }
        }
    }

    private HttpServer.Response onUpload(HttpServer.Request request) throws IOException {
        Map<String, String> query = request.query();
        String sessionId = query.get("sessionId");
        String fileId = query.get("fileId");
        String token = query.get("token");
        if (sessionId == null || fileId == null || token == null) {
            return HttpServer.Response.message(400, "Missing parameters");
        }

        ActiveSession session;
        SessionFile file;
        TransferFile transferFile = null;
        synchronized (this) {
            session = active;
            if (session == null || !session.sessionId.equals(sessionId)
                    || !session.senderIp.equals(request.clientIp)) {
                return HttpServer.Response.message(403, "Invalid token");
            }
        }
        synchronized (session.fileLock) {
            file = session.files.get(fileId);
            if (file == null || !file.token.equals(token)
                    || file.status != TransferFile.STATUS_PENDING) {
                return HttpServer.Response.message(403, "Invalid token");
            }
            file.status = TransferFile.STATUS_ACTIVE;
        }
        for (TransferFile candidate : session.uiSession.files) {
            if (fileId.equals(candidate.id)) {
                transferFile = candidate;
                break;
            }
        }
        if (transferFile == null) {
            return HttpServer.Response.message(403, "Invalid token");
        }

        TransferSession uiSession = session.uiSession;
        PeerInfo sender = session.sender;

        long expected = file.dto.size;
        long contentLength = request.contentLength();
        if (contentLength >= 0 && expected >= 0 && contentLength != expected) {
            Logs.w(TAG, "size mismatch header=" + contentLength + " expected=" + expected);
        }

        com.alan.fasttransfer.core.util.FileStorage.Target target = null;
        try {
            boolean asText = isTextMessage(file.dto);
            long saveSize = expected >= 0 ? expected : contentLength;

            byte[] textBytes = null;
            if (asText) {
                // 小体积文本先读进内存，既能展示也能落盘
                int limit = (int) Math.min(
                        saveSize > 0 ? saveSize : LocalsendProtocol.MAX_TEXT_BYTES,
                        LocalsendProtocol.MAX_TEXT_BYTES);
                textBytes = HttpServer.readFully(request.body, limit);
                transferFile.transferred = textBytes.length;
            } else {
                target = storage.openTarget(file.dto.fileName, file.dto.fileType,
                        com.alan.fasttransfer.core.util.SaveLocation.currentTreeUri(settings),
                        com.alan.fasttransfer.core.util.SaveLocation
                                .isManualPath(settings));
                transferFile.savedPath = target.displayPath;
                byte[] buffer = new byte[64 * 1024];
                long written = 0;
                int n;
                while ((n = request.body.read(buffer)) > 0) {
                    target.stream.write(buffer, 0, n);
                    written += n;
                    transferFile.transferred = written;
                    notifyProgressThrottled(uiSession);
                    if (uiSession.cancelled) {
                        throw new IOException("cancelled");
                    }
                }
                target.stream.flush();
            }

            if (asText && textBytes != null) {
                // 按 LocalSend 约定：小体积 text/plain 视为「文字消息」，
                // 直接展示内容；同时把原文留档一份，便于用户之后查找。
                final String text = new String(textBytes, "UTF-8");
                transferFile.textContent = text;
                notifyTextReceived(sender, text, null);

                try {
                    com.alan.fasttransfer.core.util.FileStorage.Target textTarget =
                            storage.openTarget(file.dto.fileName, "text/plain",
                                    com.alan.fasttransfer.core.util.SaveLocation
                                            .currentTreeUri(settings),
                                    com.alan.fasttransfer.core.util.SaveLocation
                                            .isManualPath(settings));
                    try {
                        textTarget.stream.write(textBytes);
                        textTarget.stream.flush();
                        storage.publish(textTarget);
                        transferFile.savedPath = textTarget.displayPath;
                        // publish 之后 target.openUri 才算好，历史记录靠它跳转打开
                        transferFile.savedUri = textTarget.openUri == null
                                ? null : textTarget.openUri.toString();
                    } catch (Throwable t) {
                        storage.abandon(textTarget);
                    } finally {
                        Streams.closeQuietly(textTarget.stream);
                    }
                } catch (Throwable t) {
                    Logs.w(TAG, "saving text copy failed: " + t.getMessage());
                }
            } else if (target != null) {
                storage.publish(target);
                // 记下可打开的位置：历史记录里点一下就能用别的应用打开它
                transferFile.savedUri = target.openUri == null
                        ? null : target.openUri.toString();
            }

            if (contentLength > 0) {
                transferFile.transferred = contentLength;
            } else {
                transferFile.transferred = transferFile.size;
            }
            transferFile.status = TransferFile.STATUS_DONE;
            synchronized (session.fileLock) {
                file.status = TransferFile.STATUS_DONE;
            }
            Logs.d(TAG, "received " + file.dto.fileName + " (" + transferFile.transferred + " bytes)");
        } catch (Throwable t) {
            if (target != null) {
                storage.abandon(target);
            }
            transferFile.status = TransferFile.STATUS_FAILED;
            transferFile.error = t.getMessage();
            synchronized (session.fileLock) {
                file.status = TransferFile.STATUS_FAILED;
            }
            Logs.w(TAG, "upload failed: " + t.getMessage());
            notifySessionUpdated(uiSession);
            if (t instanceof IOException) {
                throw (IOException) t;
            }
            return HttpServer.Response.message(500, "Save failed");
        } finally {
            if (target != null) {
                Streams.closeQuietly(target.stream);
            }
        }

        notifySessionUpdated(uiSession);
        finishIfComplete(session);
        return HttpServer.Response.empty(200);
    }

    private boolean isTextMessage(FileDto dto) {
        if (dto.fileType == null || !dto.fileType.startsWith("text/")) {
            return false;
        }
        long size = dto.size;
        return size >= 0 && size <= LocalsendProtocol.MAX_TEXT_BYTES;
    }

    private HttpServer.Response onCancel(HttpServer.Request request) throws IOException {
        request.readBody();
        Map<String, String> query = request.query();
        String sessionId = query.get("sessionId");

        synchronized (this) {
            if (active != null && active.sessionId.equals(sessionId)
                    && active.senderIp.equals(request.clientIp)) {
                active.uiSession.state = TransferSession.STATE_CANCELLED;
                active.uiSession.finishedAt = System.currentTimeMillis();
                active.uiSession.cancelled = true;
                TransferSession finished = active.uiSession;
                active = null;
                notifySessionFinished(finished);
                return HttpServer.Response.empty(200);
            }
            if (pending != null && pending.senderIp.equals(request.clientIp)
                    && (sessionId == null || sessionId.equals(pending.sessionId))) {
                final PendingRequest request2 = pending;
                pending = null;
                synchronized (request2.lock) {
                    request2.answered = true;
                    request2.accepted = false;
                    request2.lock.notifyAll();
                }
                final String id = request2.sessionId;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        for (Listener listener : listeners) {
                            listener.onRequestCancelled(id);
                        }
                    }
                });
                return HttpServer.Response.empty(200);
            }
        }
        return HttpServer.Response.empty(200);
    }

    private void finishIfComplete(ActiveSession session) {
        boolean complete = true;
        synchronized (session.fileLock) {
            for (SessionFile file : session.files.values()) {
                if (file.status == TransferFile.STATUS_PENDING
                        || file.status == TransferFile.STATUS_ACTIVE) {
                    complete = false;
                    break;
                }
            }
        }
        if (!complete) {
            return;
        }
        TransferSession uiSession = session.uiSession;
        boolean anyFailed = false;
        for (TransferFile file : uiSession.files) {
            if (file.status == TransferFile.STATUS_FAILED) {
                anyFailed = true;
                break;
            }
        }
        uiSession.state = anyFailed ? TransferSession.STATE_FAILED : TransferSession.STATE_DONE;
        uiSession.finishedAt = System.currentTimeMillis();
        synchronized (this) {
            if (active == session) {
                active = null;
            }
        }
        notifySessionFinished(uiSession);
    }

    /** 接收侧主动取消当前会话（例如用户点了取消）。 */
    public void cancelActiveSession() {
        ActiveSession session;
        synchronized (this) {
            session = active;
            active = null;
        }
        if (session == null) {
            return;
        }
        session.uiSession.cancelled = true;
        session.uiSession.state = TransferSession.STATE_CANCELLED;
        session.uiSession.finishedAt = System.currentTimeMillis();
        notifySessionFinished(session.uiSession);

        // 通知发送端
        final PeerInfo sender = session.sender;
        final String sessionId = session.sessionId;
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpUtil.postEmpty(HttpUtil.buildUrl(sender.ip, sender.port,
                        LocalsendProtocol.PATH_CANCEL, "sessionId", sessionId));
            }
        }, "cancel-notify").start();
    }

    // ==================== 回调分发 ====================

    private long lastProgressNotify;

    private void notifyProgressThrottled(TransferSession session) {
        long now = System.currentTimeMillis();
        if (now - lastProgressNotify < 100) {
            return;
        }
        lastProgressNotify = now;
        notifySessionUpdated(session);
    }

    private void notifyIncomingRequest(final PendingRequest request) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) {
                    listener.onIncomingRequest(request);
                }
            }
        });
    }

    private void notifySessionUpdated(final TransferSession session) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) {
                    listener.onSessionUpdated(session);
                }
            }
        });
    }

    private void notifySessionFinished(final TransferSession session) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) {
                    listener.onSessionFinished(session);
                }
            }
        });
    }

    private void notifyTextReceived(final PeerInfo from, final String text, final String path) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) {
                    listener.onTextReceived(from, text, path);
                }
            }
        });
    }
}
