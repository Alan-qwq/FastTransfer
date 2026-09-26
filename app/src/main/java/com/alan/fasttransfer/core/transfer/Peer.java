package com.alan.fasttransfer.core.transfer;

import com.alan.fasttransfer.core.LocalsendProtocol;

/**
 * 网络中发现到的一台设备。
 */
public class Peer {

    public String alias = "";
    public String deviceModel = "";
    public String deviceType = LocalsendProtocol.DEVICE_TYPE_DESKTOP;
    public String fingerprint = "";
    public String ip = "";
    public int port = LocalsendProtocol.DEFAULT_PORT;
    public String protocol = LocalsendProtocol.PROTOCOL_HTTP;
    public long lastSeen;
    /** 手动添加的设备不会因超时被移除。 */
    public boolean manual;
    /** 最近一次注册是否成功。 */
    public boolean reachable = true;
    /**
     * 虚拟设备：电脑浏览器（网页传输）。
     *
     * <p>它不是网络上发现的设备，而是「手机自己的网页服务」在设备列表里的入口。
     * 点击它不会走 LocalSend 协议，而是把内容挂到待发队列，等电脑浏览器来取。</p>
     */
    public boolean web;

    /** 虚拟网页设备的固定 key，保证它不会和真实设备混淆。 */
    public static final String WEB_KEY = "web:pc";

    public String key() {
        if (web) {
            return WEB_KEY;
        }
        if (fingerprint != null && !fingerprint.isEmpty()) {
            return fingerprint;
        }
        return ip + ":" + port;
    }

    public String displayName() {
        if (alias != null && !alias.isEmpty()) {
            return alias;
        }
        return ip;
    }

    public String subtitle() {
        if (web) {
            return deviceModel == null ? "" : deviceModel;
        }
        StringBuilder sb = new StringBuilder();
        if (deviceModel != null && !deviceModel.isEmpty()
                && !deviceModel.equalsIgnoreCase(alias)) {
            sb.append(deviceModel);
        }
        if (ip != null && !ip.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(ip);
        }
        return sb.toString();
    }

    public boolean isStale(long now, long timeoutMs) {
        // 虚拟设备没有「心跳」这回事，永远不该被超时清掉
        return !web && !manual && now - lastSeen > timeoutMs;
    }

    public void copyFrom(Peer other) {
        this.alias = other.alias;
        this.deviceModel = other.deviceModel;
        this.deviceType = other.deviceType;
        this.fingerprint = other.fingerprint;
        this.ip = other.ip;
        this.port = other.port;
        this.protocol = other.protocol;
        this.lastSeen = other.lastSeen;
        this.reachable = other.reachable;
        this.web = other.web;
    }
}
