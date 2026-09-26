package com.alan.fasttransfer.core.transfer;

import com.alan.fasttransfer.core.dto.DeviceInfoDto;

/**
 * 一台准备发送/接收的设备信息（用于传输会话）。
 */
public class PeerInfo {

    public String alias = "";
    public String deviceModel = "";
    public String deviceType = "";
    public String fingerprint = "";
    public String ip = "";
    public int port;

    public static PeerInfo from(Peer peer) {
        PeerInfo info = new PeerInfo();
        info.alias = peer.alias;
        info.deviceModel = peer.deviceModel;
        info.deviceType = peer.deviceType;
        info.fingerprint = peer.fingerprint;
        info.ip = peer.ip;
        info.port = peer.port;
        return info;
    }

    public static PeerInfo from(DeviceInfoDto dto, String ip) {
        PeerInfo info = new PeerInfo();
        info.alias = dto.alias == null ? "" : dto.alias;
        info.deviceModel = dto.deviceModel == null ? "" : dto.deviceModel;
        info.deviceType = dto.deviceType == null ? "" : dto.deviceType;
        info.fingerprint = dto.fingerprint();
        info.ip = ip;
        info.port = dto.port;
        return info;
    }

    public String displayName() {
        return alias == null || alias.isEmpty() ? ip : alias;
    }
}
