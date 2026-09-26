package com.alan.fasttransfer.core;

/**
 * LocalSend v2.2 协议常量。
 *
 * <p>参考官方实现 localsend/localsend 的 packages/core：
 * 组播组 224.0.0.167、默认端口 53317、协议版本 "2.2"。</p>
 */
public final class LocalsendProtocol {

    private LocalsendProtocol() {
    }

    /** 组播地址：位于 224.0.0.0/24，部分安卓设备只有该网段能收到 UDP 组播。 */
    public static final String MULTICAST_GROUP = "224.0.0.167";

    /** 默认端口，与 HTTP 服务端口一致。 */
    public static final int DEFAULT_PORT = 53317;

    /** 本实现遵循的协议版本（major.minor）。 */
    public static final String VERSION = "2.2";

    /** 设备类型。 */
    public static final String DEVICE_TYPE_MOBILE = "mobile";
    public static final String DEVICE_TYPE_DESKTOP = "desktop";
    public static final String DEVICE_TYPE_WEB = "web";
    public static final String DEVICE_TYPE_HEADLESS = "headless";
    public static final String DEVICE_TYPE_SERVER = "server";

    /** 协议类型。 */
    public static final String PROTOCOL_HTTP = "http";
    public static final String PROTOCOL_HTTPS = "https";

    public static final String API_PREFIX = "/api/localsend/v2";
    public static final String PATH_REGISTER = API_PREFIX + "/register";
    public static final String PATH_INFO = API_PREFIX + "/info";
    public static final String PATH_PREPARE_UPLOAD = API_PREFIX + "/prepare-upload";
    public static final String PATH_UPLOAD = API_PREFIX + "/upload";
    public static final String PATH_CANCEL = API_PREFIX + "/cancel";
    public static final String PATH_PREPARE_DOWNLOAD = API_PREFIX + "/prepare-download";
    public static final String PATH_DOWNLOAD = API_PREFIX + "/download";

    /** 文字消息作为 text/plain 的“文件”发送，文件名沿用 LocalSend 约定。 */
    public static final String TEXT_FILE_NAME = "message.txt";
    public static final String TEXT_MIME = "text/plain";

    /** 文字消息体积上限（超过则不再当作文字展示）。 */
    public static final int MAX_TEXT_BYTES = 1024 * 1024;

    /** prepare-upload 等待用户确认的最长时间（毫秒）。 */
    public static final long PREPARE_DECISION_TIMEOUT_MS = 60_000L;

    /** 单个 PIN 允许的失败次数，超过返回 429。 */
    public static final int MAX_PIN_ATTEMPTS = 3;

    /** 未知设备类型按协议回落到 desktop（协议 7.1 节）。 */
    public static String normalizeDeviceType(String type) {
        if (type == null) {
            return null;
        }
        switch (type) {
            case DEVICE_TYPE_MOBILE:
            case DEVICE_TYPE_DESKTOP:
            case DEVICE_TYPE_WEB:
            case DEVICE_TYPE_HEADLESS:
            case DEVICE_TYPE_SERVER:
                return type;
            default:
                return DEVICE_TYPE_DESKTOP;
        }
    }
}
