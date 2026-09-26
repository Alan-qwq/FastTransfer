package com.alan.fasttransfer.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.alan.fasttransfer.core.dto.DeviceInfoDto;
import com.alan.fasttransfer.core.dto.PrepareUploadRequestDto;
import com.alan.fasttransfer.core.dto.PrepareUploadResponseDto;
import com.alan.fasttransfer.core.dto.FileDto;
import com.alan.fasttransfer.core.net.HttpResult;
import com.alan.fasttransfer.core.net.HttpUtil;
import com.alan.fasttransfer.core.transfer.Peer;
import com.alan.fasttransfer.core.transfer.PeerInfo;
import com.alan.fasttransfer.core.transfer.SendItem;
import com.alan.fasttransfer.core.transfer.TransferFile;
import com.alan.fasttransfer.core.transfer.TransferSession;
import com.alan.fasttransfer.core.util.Json;
import com.alan.fasttransfer.core.util.Logs;
import com.alan.fasttransfer.core.util.MimeTypes;
import com.alan.fasttransfer.core.util.Streams;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 发送侧：向目标设备发起 prepare-upload 并逐文件上传。
 */
public class SendManager {

    private static final String TAG = "SendManager";

    /** 发送结果。 */
    public interface Callback {
        void onFinished(TransferSession session);
    }

    private final Context appContext;
    private final AppSettings settings;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile TransferSession current;
    private volatile Callback callback;

    /** 进度回调。 */
    public interface Listener {
        void onSessionUpdated(TransferSession session);

        void onSessionFinished(TransferSession session);
    }

    public SendManager(Context context, AppSettings settings) {
        this.appContext = context.getApplicationContext();
        this.settings = settings;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public TransferSession currentSession() {
        return current;
    }

    public boolean isBusy() {
        TransferSession session = current;
        return session != null && !session.isFinished();
    }

    /** 取消正在进行的发送。 */
    public void cancel() {
        TransferSession session = current;
        if (session != null && !session.isFinished()) {
            session.cancelled = true;
            Logs.d(TAG, "cancel requested");
        }
    }

    /** 发送文件 / 文字。 */
    public TransferSession send(final Peer target, final List<SendItem> items,
                                final String pin, final Callback callback) {
        if (isBusy()) {
            return null;
        }
        final TransferSession session = new TransferSession();
        session.id = UUID.randomUUID().toString();
        session.direction = TransferSession.DIRECTION_SEND;
        session.peer = PeerInfo.from(target);
        session.state = TransferSession.STATE_PREPARING;
        session.pinRequired = pin != null && !pin.isEmpty();
        for (SendItem item : items) {
            TransferFile file = new TransferFile();
            file.id = UUID.randomUUID().toString().replace("-", "");
            file.name = item.name;
            file.size = item.size;
            file.mime = item.mime == null ? MimeTypes.fromName(item.name) : item.mime;
            file.item = item;
            session.files.add(file);
        }
        current = session;
        this.callback = callback;
        notifyUpdated(session);

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runSend(session, target, pin);
            }
        }, "send-worker");
        worker.setDaemon(true);
        worker.start();
        return session;
    }

    private void runSend(TransferSession session, Peer target, String pin) {
        String base = HttpUtil.baseUrl(target.ip, target.port);
        try {
            // 1) 元数据准备
            PrepareUploadRequestDto request = new PrepareUploadRequestDto();
            request.info = DeviceInfoDto.of(settings.getAlias(), null,
                    LocalsendProtocol.DEVICE_TYPE_MOBILE, settings.getFingerprint(),
                    target.port);
            for (TransferFile file : session.files) {
                request.files.put(file.id, file.toDto());
            }

            String url;
            if (pin != null && !pin.isEmpty()) {
                url = HttpUtil.buildUrl(target.ip, target.port,
                        LocalsendProtocol.PATH_PREPARE_UPLOAD, "pin", pin);
            } else {
                url = HttpUtil.buildUrl(target.ip, target.port,
                        LocalsendProtocol.PATH_PREPARE_UPLOAD);
            }

            session.state = TransferSession.STATE_WAITING_PEER;
            notifyUpdated(session);

            HttpResult prepare = HttpUtil.postJson(url, Json.toJson(request));
            if (session.cancelled) {
                finish(session, TransferSession.STATE_CANCELLED, null);
                return;
            }
            if (prepare.isNetworkError()) {
                finish(session, TransferSession.STATE_FAILED, prepare.errorMessage);
                return;
            }

            switch (prepare.status) {
                case 200:
                    break;
                case 204:
                    finish(session, TransferSession.STATE_DECLINED, "no-files-accepted");
                    return;
                case 401:
                    finish(session, TransferSession.STATE_FAILED,
                            session.pinRequired ? "pin-wrong" : "pin-required");
                    return;
                case 429:
                    finish(session, TransferSession.STATE_FAILED, "pin-locked");
                    return;
                case 403:
                    finish(session, TransferSession.STATE_DECLINED, "declined");
                    return;
                case 409:
                    finish(session, TransferSession.STATE_FAILED, "busy");
                    return;
                default:
                    finish(session, TransferSession.STATE_FAILED, "http-" + prepare.status);
                    return;
            }

            PrepareUploadResponseDto response = Json.fromJson(prepare.body,
                    PrepareUploadResponseDto.class);
            if (response == null || response.sessionId == null || response.files == null
                    || response.files.isEmpty()) {
                finish(session, TransferSession.STATE_FAILED, "bad-response");
                return;
            }
            session.remoteSessionId = response.sessionId;

            // 2) 逐个上传
            session.state = TransferSession.STATE_RUNNING;
            notifyUpdated(session);

            boolean anyFailed = false;
            for (TransferFile file : session.files) {
                if (session.cancelled) {
                    break;
                }
                String token = response.files.get(file.id);
                if (token == null) {
                    file.status = TransferFile.STATUS_SKIPPED;
                    file.error = "not-accepted";
                    continue;
                }
                boolean ok = uploadOne(session, target, file, response.sessionId, token);
                if (!ok && !session.cancelled) {
                    anyFailed = true;
                }
            }

            if (session.cancelled) {
                cancelRemote(target, response.sessionId);
                finish(session, TransferSession.STATE_CANCELLED, null);
            } else if (anyFailed) {
                finish(session, TransferSession.STATE_FAILED, "some-files-failed");
            } else {
                finish(session, TransferSession.STATE_DONE, null);
            }
        } catch (Throwable t) {
            Logs.e(TAG, "send failed", t);
            finish(session, TransferSession.STATE_FAILED, t.getMessage());
        }
    }

    private boolean uploadOne(final TransferSession session, Peer target,
                              final TransferFile file, String sessionId, String token) {
        file.status = TransferFile.STATUS_ACTIVE;
        file.transferred = 0;
        final long[] lastNotify = {0L};
        InputStream in = null;
        try {
            long size = file.item.resolveSize(appContext);
            file.size = size;
            in = file.item.open(appContext);

            String url = HttpUtil.buildUrl(target.ip, target.port, LocalsendProtocol.PATH_UPLOAD,
                    "sessionId", sessionId, "fileId", file.id, "token", token);

            HttpResult result = HttpUtil.postStream(url, file.mime, size, in,
                    new HttpUtil.ProgressListener() {
                        @Override
                        public boolean onProgress(long sent, long total) {
                            file.transferred = sent;
                            long now = System.currentTimeMillis();
                            if (now - lastNotify[0] >= 100) {
                                lastNotify[0] = now;
                                notifyUpdated(session);
                            }
                            return !session.cancelled;
                        }
                    });

            if (result.isNetworkError()) {
                file.status = TransferFile.STATUS_FAILED;
                file.error = result.errorMessage;
                file.transferred = 0;
                return false;
            }
            if (result.status == 200) {
                file.status = TransferFile.STATUS_DONE;
                if (file.size >= 0) {
                    file.transferred = file.size;
                }
                notifyUpdated(session);
                return true;
            }
            file.status = TransferFile.STATUS_FAILED;
            file.error = "http-" + result.status;
            notifyUpdated(session);
            return false;
        } catch (Throwable t) {
            file.status = TransferFile.STATUS_FAILED;
            file.error = t.getMessage();
            notifyUpdated(session);
            return false;
        } finally {
            Streams.closeQuietly(in);
        }
    }

    private void cancelRemote(final Peer target, final String sessionId) {
        try {
            HttpUtil.postEmpty(HttpUtil.buildUrl(target.ip, target.port,
                    LocalsendProtocol.PATH_CANCEL, "sessionId", sessionId));
        } catch (Throwable ignored) {
        }
    }

    private void finish(TransferSession session, int state, String error) {
        session.state = state;
        session.error = error;
        session.finishedAt = System.currentTimeMillis();
        current = session;
        final Callback cb = callback;
        callback = null;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) {
                    listener.onSessionFinished(session);
                }
                if (cb != null) {
                    cb.onFinished(session);
                }
            }
        });
    }

    private void notifyUpdated(final TransferSession session) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) {
                    listener.onSessionUpdated(session);
                }
            }
        });
    }

    /** 便捷方法：发送一段文字。 */
    public static List<SendItem> textItems(String text) {
        List<SendItem> items = new ArrayList<>();
        items.add(SendItem.fromText(text));
        return items;
    }
}
