package com.alan.fasttransfer.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.alan.fasttransfer.core.dto.DeviceInfoDto;
import com.alan.fasttransfer.core.net.HttpResult;
import com.alan.fasttransfer.core.net.HttpUtil;
import com.alan.fasttransfer.core.net.MulticastDiscovery;
import com.alan.fasttransfer.core.net.NetworkUtils;
import com.alan.fasttransfer.core.transfer.HistoryEntry;
import com.alan.fasttransfer.core.transfer.Peer;
import com.alan.fasttransfer.core.transfer.PeerInfo;
import com.alan.fasttransfer.core.transfer.SendItem;
import com.alan.fasttransfer.core.transfer.TransferHistory;
import com.alan.fasttransfer.core.transfer.TransferSession;
import com.alan.fasttransfer.core.util.FileStorage;
import com.alan.fasttransfer.core.util.Json;
import com.alan.fasttransfer.core.util.Logs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 传输引擎：把发现、接收服务端、发送客户端与历史记录整合在一起。
 */
public class TransferEngine implements MulticastDiscovery.Listener,
        SendManager.Listener, ReceiveManager.Listener {

    private static final String TAG = "TransferEngine";
    private static final long PEER_TIMEOUT_MS = 90_000L;
    private static final long ANNOUNCE_INTERVAL_MS = 10_000L;

    /** 设备与状态变化的回调。 */
    public interface Listener {
        void onPeersChanged();

        void onIncomingRequest(ReceiveManager.PendingRequest request);

        void onRequestCancelled(String sessionId);

        void onSessionUpdated(TransferSession session);

        void onSessionFinished(TransferSession session);

        void onTextReceived(PeerInfo from, String text, String savedPath);

        void onEngineStateChanged();

        /**
         * 传输记录多了一条（例如网页上传完成）。
         *
         * <p>给默认实现是为了不影响既有的监听者 —— 它们大多只关心设备状态。</p>
         */
        default void onHistoryChanged() {
        }
    }

    private static volatile TransferEngine instance;

    public static TransferEngine get(Context context) {
        if (instance == null) {
            synchronized (TransferEngine.class) {
                if (instance == null) {
                    instance = new TransferEngine(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final Context appContext;
    private final AppSettings settings;
    private final FileStorage storage;
    private final TransferHistory history;
    private final MulticastDiscovery discovery;
    private final ReceiveManager receiveManager;
    private final SendManager sendManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final ExecutorService probePool = Executors.newFixedThreadPool(24);

    private volatile boolean visible = true;
    private volatile boolean started;
    private Thread announceThread;
    private long lastLocalIp;

    private TransferEngine(Context context) {
        this.appContext = context;
        this.settings = new AppSettings(context);
        this.storage = new FileStorage(context);
        this.history = new TransferHistory(settings);
        this.discovery = new MulticastDiscovery(context, this);
        this.receiveManager = new ReceiveManager(settings, storage);
        this.sendManager = new SendManager(context, settings);
        this.sendManager.addListener(this);
        this.receiveManager.addListener(this);
        // 网页上传不走 LocalSend 会话，补一条记录进历史，否则「传输记录」里看不到
        this.receiveManager.setWebUploadListener(
                new com.alan.fasttransfer.core.web.WebTransfer.UploadListener() {
                    @Override
                    public void onWebUploadFinished(String clientIp, java.util.List<String> names,
                                                    long totalBytes,
                                                    java.util.List<String> savedUris,
                                                    String firstMime, String firstPath) {
                        recordWebUpload(clientIp, names, totalBytes, savedUris,
                                firstMime, firstPath);
                    }
                });
        this.visible = settings.isVisible();
    }

    /** 把一次网页上传写成和局域网接收一样的传输记录。 */
    private void recordWebUpload(String clientIp, java.util.List<String> names, long totalBytes,
                                 java.util.List<String> savedUris, String firstMime,
                                 String firstPath) {
        if (names == null || names.isEmpty()) {
            return;
        }
        HistoryEntry entry = new HistoryEntry();
        entry.id = java.util.UUID.randomUUID().toString();
        entry.direction = TransferSession.DIRECTION_RECEIVE;
        // 来源显示成「浏览器」，和发送页设备列表里的叫法保持一致
        entry.peerAlias = "浏览器";
        entry.peerIp = clientIp == null ? "" : clientIp;
        entry.fileCount = names.size();
        entry.totalBytes = totalBytes;
        entry.statusKey = "success";
        long now = System.currentTimeMillis();
        entry.startedAt = now;
        entry.finishedAt = now;
        entry.firstFileName = names.get(0);
        entry.firstMime = firstMime == null ? "" : firstMime;
        entry.savedUris = savedUris;
        entry.savedPathHint = firstPath == null ? "" : firstPath;
        history.add(entry);
        Logs.d("TransferEngine", "web upload recorded: " + entry.firstFileName);

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) {
                    listener.onHistoryChanged();
                }
            }
        });
    }

    public AppSettings settings() {
        return settings;
    }

    public TransferHistory history() {
        return history;
    }

    public FileStorage storage() {
        return storage;
    }

    public SendManager sendManager() {
        return sendManager;
    }

    public ReceiveManager receiveManager() {
        return receiveManager;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isVisible() {
        return visible;
    }

    public String localIp() {
        return NetworkUtils.getLocalIpv4();
    }

    public int localPort() {
        return receiveManager.port();
    }

    /** 启动接收服务与设备发现。 */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        boolean bound = receiveManager.start();
        if (!bound) {
            Logs.w(TAG, "receive server failed to bind");
        }
        if (visible) {
            discovery.updateLocalInfo(settings.getAlias(), null,
                    LocalsendProtocol.DEVICE_TYPE_MOBILE, settings.getFingerprint(),
                    settings.getPort());
            discovery.start();
            startAnnounceLoop();
        }
        notifyEngineState();
    }

    public synchronized void stop() {
        started = false;
        stopAnnounceLoop();
        discovery.stop();
        receiveManager.stop();
        notifyEngineState();
    }

    /** 是否处于可被发现的广播状态。 */
    public void setVisible(boolean value) {
        settings.setVisible(value);
        visible = value;
        if (value) {
            discovery.updateLocalInfo(settings.getAlias(), null,
                    LocalsendProtocol.DEVICE_TYPE_MOBILE, settings.getFingerprint(),
                    settings.getPort());
            discovery.start();
            discovery.announceAsync();
            startAnnounceLoop();
        } else {
            stopAnnounceLoop();
            discovery.stop();
        }
        notifyEngineState();
    }

    /** 设置（设备名/端口）变更后刷新广播信息。 */
    public void refreshLocalInfo() {
        discovery.updateLocalInfo(settings.getAlias(), null,
                LocalsendProtocol.DEVICE_TYPE_MOBILE, settings.getFingerprint(),
                settings.getPort());
        if (visible && started) {
            discovery.announceAsync();
        }
        notifyEngineState();
    }

    private void startAnnounceLoop() {
        if (announceThread != null) {
            return;
        }
        announceThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (visible && started) {
                    try {
                        Thread.sleep(ANNOUNCE_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    long ip = System.currentTimeMillis();
                    if (ip - lastLocalIp > 5000) {
                        lastLocalIp = ip;
                        notifyPeersChanged();
                    }
                    prunePeers();
                    discovery.announceAsync();
                }
            }
        }, "announce-loop");
        announceThread.setDaemon(true);
        announceThread.start();
    }

    private void stopAnnounceLoop() {
        Thread thread = announceThread;
        announceThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void prunePeers() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<String, Peer> entry : peers.entrySet()) {
            if (entry.getValue().isStale(now, PEER_TIMEOUT_MS)) {
                peers.remove(entry.getKey());
                changed = true;
            }
        }
        if (changed) {
            notifyPeersChanged();
        }
    }

    public List<Peer> peers() {
        List<Peer> list = new ArrayList<>(peers.values());
        Collections.sort(list, new Comparator<Peer>() {
            @Override
            public int compare(Peer a, Peer b) {
                if (a.reachable != b.reachable) {
                    return a.reachable ? -1 : 1;
                }
                return Long.compare(b.lastSeen, a.lastSeen);
            }
        });
        return list;
    }

    public Peer findPeer(String ipOrKey) {
        if (ipOrKey == null) {
            return null;
        }
        for (Peer peer : peers.values()) {
            if (ipOrKey.equals(peer.ip) || ipOrKey.equals(peer.key())
                    || ipOrKey.equals(peer.alias)) {
                return peer;
            }
        }
        return null;
    }

    // ==================== 发现回调 ====================

    @Override
    public void onAnnouncement(final DeviceInfoDto info, final String sourceIp) {
        final Peer peer = new Peer();
        peer.alias = info.alias == null ? sourceIp : info.alias;
        peer.deviceModel = info.deviceModel == null ? "" : info.deviceModel;
        peer.deviceType = LocalsendProtocol.normalizeDeviceType(info.deviceType);
        if (peer.deviceType == null) {
            peer.deviceType = LocalsendProtocol.DEVICE_TYPE_DESKTOP;
        }
        peer.fingerprint = info.fingerprint();
        peer.ip = sourceIp;
        peer.port = info.port > 0 ? info.port : LocalsendProtocol.DEFAULT_PORT;
        peer.protocol = info.protocol == null ? LocalsendProtocol.PROTOCOL_HTTP : info.protocol;
        peer.lastSeen = System.currentTimeMillis();

        upsertPeer(peer);
        // 按协议要求回一个 register 请求，同时拿到对方的准确信息
        new Thread(new Runnable() {
            @Override
            public void run() {
                registerWith(peer);
            }
        }, "register-answer").start();
    }

    @Override
    public void onAvailabilityChanged(boolean available, String reason) {
        Logs.d(TAG, "multicast available=" + available + " reason=" + reason);
    }

    private void upsertPeer(Peer incoming) {
        String key = incoming.key();
        Peer existing = peers.get(key);
        if (existing == null) {
            peers.put(key, incoming);
            notifyPeersChanged();
            return;
        }
        existing.copyFrom(incoming);
        notifyPeersChanged();
    }

    /** 向对方发送 register，成功后把对方信息补充完整。 */
    private void registerWith(Peer peer) {
        try {
            DeviceInfoDto mine = DeviceInfoDto.of(settings.getAlias(), null,
                    LocalsendProtocol.DEVICE_TYPE_MOBILE, settings.getFingerprint(),
                    settings.getPort());
            String url = HttpUtil.buildUrl(peer.ip, peer.port, LocalsendProtocol.PATH_REGISTER);
            HttpResult result = HttpUtil.postJson(url, Json.toJson(mine));
            if (result.isOk() && result.body != null && !result.body.isEmpty()) {
                DeviceInfoDto info = Json.fromJson(result.body, DeviceInfoDto.class);
                if (info != null) {
                    if (info.alias != null && !info.alias.isEmpty()) {
                        peer.alias = info.alias;
                    }
                    if (info.deviceModel != null) {
                        peer.deviceModel = info.deviceModel;
                    }
                    String type = LocalsendProtocol.normalizeDeviceType(info.deviceType);
                    if (type != null) {
                        peer.deviceType = type;
                    }
                }
                peer.reachable = true;
            } else {
                peer.reachable = false;
            }
        } catch (Throwable t) {
            peer.reachable = false;
        }
        peer.lastSeen = System.currentTimeMillis();
        notifyPeersChanged();
    }

    /** 手动添加 / 探测一台设备（IP 或 IP:端口）。 */
    public void addPeerByAddress(String input, final ProbeCallback callback) {
        final String[] hostPort = NetworkUtils.splitHostPort(input, settings.getPort());
        if (hostPort == null) {
            if (callback != null) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResult(null, "invalid-ip");
                    }
                });
            }
            return;
        }
        final String ip = hostPort[0];
        final int port = Integer.parseInt(hostPort[1]);
        probePool.execute(new Runnable() {
            @Override
            public void run() {
                Peer peer = probeHost(ip, port, true);
                if (callback != null) {
                    final Peer result = peer;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onResult(result, result == null ? "unreachable" : null);
                        }
                    });
                }
            }
        });
    }

    public interface ProbeCallback {
        void onResult(Peer peer, String error);
    }

    /** 探测单个地址；注册成功即认为是一台可用设备。 */
    private Peer probeHost(String ip, int port, boolean manual) {
        try {
            DeviceInfoDto mine = DeviceInfoDto.of(settings.getAlias(), null,
                    LocalsendProtocol.DEVICE_TYPE_MOBILE, settings.getFingerprint(), settings.getPort());
            HttpResult result = HttpUtil.postJson(
                    HttpUtil.buildUrl(ip, port, LocalsendProtocol.PATH_REGISTER),
                    Json.toJson(mine));
            DeviceInfoDto info = result.isOk() && result.body != null
                    ? Json.fromJson(result.body, DeviceInfoDto.class) : null;
            if (info == null) {
                return null;
            }
            Peer peer = new Peer();
            peer.alias = info.alias == null || info.alias.isEmpty() ? ip : info.alias;
            peer.deviceModel = info.deviceModel == null ? "" : info.deviceModel;
            peer.deviceType = LocalsendProtocol.normalizeDeviceType(info.deviceType);
            if (peer.deviceType == null) {
                peer.deviceType = LocalsendProtocol.DEVICE_TYPE_DESKTOP;
            }
            peer.fingerprint = info.fingerprint();
            peer.ip = ip;
            peer.port = port;
            peer.protocol = LocalsendProtocol.PROTOCOL_HTTP;
            peer.lastSeen = System.currentTimeMillis();
            peer.manual = manual;
            peer.reachable = true;
            upsertPeer(peer);
            return peer;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在当前 /24 网段内并发探测，作为组播不可用时的兜底。 */
    public void scanSubnet(final ProbeCallback callback) {
        final String localIp = NetworkUtils.getLocalIpv4();
        final String prefix = NetworkUtils.subnetPrefix(localIp);
        if (prefix == null) {
            if (callback != null) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResult(null, "no-network");
                    }
                });
            }
            return;
        }
        final AtomicInteger remaining = new AtomicInteger(254);
        final List<Peer> found = Collections.synchronizedList(new ArrayList<Peer>());
        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + i;
            if (ip.equals(localIp)) {
                remaining.decrementAndGet();
                continue;
            }
            probePool.execute(new Runnable() {
                @Override
                public void run() {
                    Peer peer = probeHost(ip, settings.getPort(), false);
                    if (peer != null) {
                        found.add(peer);
                    }
                    if (remaining.decrementAndGet() == 0 && callback != null) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onResult(found.isEmpty() ? null : found.get(0), null);
                            }
                        });
                    }
                }
            });
        }
    }

    // ==================== 发送 ====================

    public TransferSession send(Peer target, List<SendItem> items, String pin,
                                SendManager.Callback callback) {
        return sendManager.send(target, items, pin, callback);
    }

    /** 计算本次需要发给对方的 PIN（仅当对方需要时才提示）。 */
    public String pinForSend(String userEnteredPin) {
        return userEnteredPin;
    }

    // ==================== 接收 ====================

    public boolean respondToRequest(String sessionId, boolean accepted, List<String> acceptedIds) {
        return receiveManager.respondToRequest(sessionId, accepted, acceptedIds);
    }

    // ==================== 回调转发 ====================

    @Override
    public void onSessionUpdated(TransferSession session) {
        for (Listener listener : listeners) {
            listener.onSessionUpdated(session);
        }
    }

    @Override
    public void onSessionFinished(TransferSession session) {
        history.add(HistoryEntry.from(session));
        for (Listener listener : listeners) {
            listener.onSessionFinished(session);
        }
    }

    @Override
    public void onIncomingRequest(ReceiveManager.PendingRequest request) {
        for (Listener listener : listeners) {
            listener.onIncomingRequest(request);
        }
    }

    @Override
    public void onRequestCancelled(String sessionId) {
        for (Listener listener : listeners) {
            listener.onRequestCancelled(sessionId);
        }
    }

    @Override
    public void onTextReceived(PeerInfo from, String text, String savedPath) {
        for (Listener listener : listeners) {
            listener.onTextReceived(from, text, savedPath);
        }
    }

    private void notifyPeersChanged() {
        if (listeners.isEmpty()) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) {
                    listener.onPeersChanged();
                }
            }
        });
    }

    private void notifyEngineState() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener listener : listeners) {
                    listener.onEngineStateChanged();
                }
            }
        });
    }

    /** 调试用：当前设备快照。 */
    public Map<String, Peer> peerSnapshot() {
        return new LinkedHashMap<>(peers);
    }

    public String describePeerKey(Peer peer) {
        return String.format(Locale.ROOT, "%s@%s:%d", peer.alias, peer.ip, peer.port);
    }
}
