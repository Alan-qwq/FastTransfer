package com.alan.fasttransfer.core.net;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.alan.fasttransfer.core.LocalsendProtocol;
import com.alan.fasttransfer.core.dto.DeviceInfoDto;
import com.alan.fasttransfer.core.util.Json;
import com.alan.fasttransfer.core.util.Logs;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP 组播设备发现（LocalSend v2.2）。
 *
 * <p>本机向 {@code 224.0.0.167:53317} 广播自身信息，同时监听该组播地址上其他设备的通报。
 * 收到通报后由上层回一个 HTTP register 请求完成双向确认。</p>
 */
public class MulticastDiscovery {

    private static final String TAG = "Multicast";

    /** 通报回调。 */
    public interface Listener {
        /** 收到其他设备的通报。 */
        void onAnnouncement(DeviceInfoDto info, String sourceIp);

        /** 组播是否可用（端口被占用等情况下会不可用）。 */
        void onAvailabilityChanged(boolean available, String reason);
    }

    private static final long[] ANNOUNCE_DELAYS = {100L, 500L, 2000L};
    private static final int RECEIVE_BUFFER = 16 * 1024;

    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    private MulticastSocket receiveSocket;
    private MulticastSocket sendSocket;
    private Thread receiveThread;
    private WifiManager.MulticastLock multicastLock;
    private volatile String fingerprint = "";
    private volatile String alias = "";
    private volatile String deviceModel = "";
    private volatile String deviceType = LocalsendProtocol.DEVICE_TYPE_MOBILE;
    private volatile int port = LocalsendProtocol.DEFAULT_PORT;
    private volatile boolean available;

    public MulticastDiscovery(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isAvailable() {
        return available;
    }

    public void updateLocalInfo(String alias, String deviceModel, String deviceType,
                                String fingerprint, int port) {
        this.alias = alias == null ? "" : alias;
        this.deviceModel = deviceModel == null ? "" : deviceModel;
        this.deviceType = deviceType == null ? LocalsendProtocol.DEVICE_TYPE_MOBILE : deviceType;
        this.fingerprint = fingerprint == null ? "" : fingerprint;
        this.port = port;
    }

    public synchronized void start() {
        if (running.get()) {
            return;
        }
        try {
            acquireMulticastLock();
            InetAddress group = InetAddress.getByName(LocalsendProtocol.MULTICAST_GROUP);

            receiveSocket = new MulticastSocket(LocalsendProtocol.DEFAULT_PORT);
            receiveSocket.setReuseAddress(true);
            receiveSocket.setTimeToLive(1);
            receiveSocket.setLoopbackMode(false);
            joinGroup(receiveSocket, group);

            sendSocket = new MulticastSocket();
            sendSocket.setTimeToLive(1);
            sendSocket.setLoopbackMode(false);
            sendSocket.setNetworkInterface(pickInterface());
        } catch (IOException e) {
            Logs.w(TAG, "multicast unavailable: " + e.getMessage());
            closeSockets();
            setAvailable(false, e.getMessage());
            return;
        }

        running.set(true);
        setAvailable(true, null);

        receiveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                receiveLoop();
            }
        }, "multicast-recv");
        receiveThread.setDaemon(true);
        receiveThread.start();

        announceAsync();
    }

    public synchronized void stop() {
        running.set(false);
        closeSockets();
        releaseMulticastLock();
        setAvailable(false, "stopped");
    }

    /** 发送一轮通报（100ms / 500ms / 2000ms 三连发，降低丢包影响）。 */
    public void announceAsync() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long previous = 0;
                for (long delay : ANNOUNCE_DELAYS) {
                    try {
                        Thread.sleep(delay - previous);
                    } catch (InterruptedException e) {
                        return;
                    }
                    previous = delay;
                    if (!running.get()) {
                        return;
                    }
                    sendAnnouncement();
                }
            }
        }, "multicast-announce");
        t.setDaemon(true);
        t.start();
    }

    private void sendAnnouncement() {
        MulticastSocket socket = sendSocket;
        if (socket == null || fingerprint.isEmpty()) {
            return;
        }
        try {
            DeviceInfoDto dto = DeviceInfoDto.of(alias, deviceModel, deviceType,
                    fingerprint, port);
            dto.announce = Boolean.TRUE;
            byte[] payload = Json.toJson(dto).getBytes("UTF-8");
            DatagramPacket packet = new DatagramPacket(payload, payload.length,
                    InetAddress.getByName(LocalsendProtocol.MULTICAST_GROUP),
                    LocalsendProtocol.DEFAULT_PORT);
            socket.send(packet);
            Logs.d(TAG, "announced as " + alias);
        } catch (IOException e) {
            Logs.d(TAG, "announce failed: " + e.getMessage());
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[RECEIVE_BUFFER];
        while (running.get()) {
            MulticastSocket socket = receiveSocket;
            if (socket == null) {
                return;
            }
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String json = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                DeviceInfoDto info = Json.fromJson(json, DeviceInfoDto.class);
                if (info == null || info.alias == null) {
                    continue;
                }
                String sourceIp = packet.getAddress() == null ? "" : packet.getAddress().getHostAddress();
                if (info.fingerprint() != null && !info.fingerprint().isEmpty()
                        && info.fingerprint().equals(fingerprint)) {
                    // 自己的回环消息
                    continue;
                }
                if (info.port <= 0) {
                    info.port = LocalsendProtocol.DEFAULT_PORT;
                }
                info.deviceType = LocalsendProtocol.normalizeDeviceType(info.deviceType);
                final DeviceInfoDto result = info;
                final String ip = sourceIp;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) {
                            listener.onAnnouncement(result, ip);
                        }
                    }
                });
            } catch (IOException e) {
                if (running.get()) {
                    Logs.d(TAG, "receive error: " + e.getMessage());
                }
            } catch (Throwable t) {
                Logs.w(TAG, "bad announcement: " + t.getMessage());
            }
        }
    }

    private void joinGroup(MulticastSocket socket, InetAddress group) throws IOException {
        NetworkInterface iface = pickInterface();
        boolean joined = false;
        if (iface != null) {
            try {
                socket.joinGroup(new InetSocketAddress(group, LocalsendProtocol.DEFAULT_PORT), iface);
                joined = true;
            } catch (Throwable t) {
                Logs.d(TAG, "joinGroup(iface) failed: " + t.getMessage());
            }
        }
        if (!joined) {
            // 老 API，在部分低版本设备上更可靠
            socket.joinGroup(group);
        }
    }

    /** 选一个已启用、支持组播的 IPv4 网卡。 */
    private NetworkInterface pickInterface() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return null;
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || !ni.supportsMulticast()) {
                        continue;
                    }
                    if (ni.getInetAddresses() == null || !hasIpv4(ni)) {
                        continue;
                    }
                    return ni;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            Logs.d(TAG, "enumerate interfaces failed: " + e.getMessage());
        }
        return null;
    }

    private boolean hasIpv4(NetworkInterface ni) {
        Enumeration<InetAddress> addresses = ni.getInetAddresses();
        while (addresses != null && addresses.hasMoreElements()) {
            InetAddress address = addresses.nextElement();
            if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                return true;
            }
        }
        return false;
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("fasttransfer-multicast");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Throwable t) {
            Logs.d(TAG, "multicast lock failed: " + t.getMessage());
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Throwable ignored) {
        }
        multicastLock = null;
    }

    private void setAvailable(boolean value, String reason) {
        if (available == value) {
            return;
        }
        available = value;
        final boolean v = value;
        final String r = reason;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) {
                    listener.onAvailabilityChanged(v, r);
                }
            }
        });
    }

    private void closeSockets() {
        MulticastSocket rs = receiveSocket;
        receiveSocket = null;
        if (rs != null) {
            try {
                rs.close();
            } catch (Throwable ignored) {
            }
        }
        MulticastSocket ss = sendSocket;
        sendSocket = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
