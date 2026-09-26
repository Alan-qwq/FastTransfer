package com.alan.fasttransfer.core.dto;

import com.google.gson.annotations.SerializedName;

import java.util.HashMap;
import java.util.Map;

/**
 * 设备注册信息，对应 LocalSend 协议中的 {@code RegisterDto}（v2 使用 camelCase 字段）。
 */
public class DeviceInfoDto {

    @SerializedName("alias")
    public String alias;

    @SerializedName("version")
    public String version;

    @SerializedName("deviceModel")
    public String deviceModel;

    @SerializedName("deviceType")
    public String deviceType;

    /** v2：设备指纹；v3：访问令牌。 */
    @SerializedName("fingerprint")
    public String fingerprint;

    /** v3 使用 token 字段，接收时兼容读取。 */
    @SerializedName("token")
    public String token;

    @SerializedName("port")
    public int port;

    @SerializedName("protocol")
    public String protocol;

    @SerializedName("download")
    public boolean download;

    /** 组播通报专用字段（协议要求带上）。 */
    @SerializedName("announce")
    public Boolean announce;

    public String fingerprint() {
        if (fingerprint != null && !fingerprint.isEmpty()) {
            return fingerprint;
        }
        return token == null ? "" : token;
    }

    public static DeviceInfoDto of(String alias, String model, String type, String fingerprint, int port) {
        DeviceInfoDto dto = new DeviceInfoDto();
        dto.alias = alias;
        dto.version = com.alan.fasttransfer.core.LocalsendProtocol.VERSION;
        dto.deviceModel = model;
        dto.deviceType = type;
        dto.fingerprint = fingerprint;
        dto.port = port;
        dto.protocol = com.alan.fasttransfer.core.LocalsendProtocol.PROTOCOL_HTTP;
        dto.download = false;
        return dto;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("alias", alias);
        map.put("version", version);
        if (deviceModel != null) {
            map.put("deviceModel", deviceModel);
        }
        if (deviceType != null) {
            map.put("deviceType", deviceType);
        }
        map.put("fingerprint", fingerprint());
        map.put("port", port);
        map.put("protocol", protocol == null ? com.alan.fasttransfer.core.LocalsendProtocol.PROTOCOL_HTTP : protocol);
        map.put("download", download);
        return map;
    }
}
