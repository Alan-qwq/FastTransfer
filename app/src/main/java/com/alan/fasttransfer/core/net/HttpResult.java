package com.alan.fasttransfer.core.net;

/**
 * 一次 HTTP 调用的结果。
 */
public class HttpResult {

    /** 网络层失败时的状态码取 0。 */
    public final int status;
    public final String body;
    public final String errorMessage;

    private HttpResult(int status, String body, String errorMessage) {
        this.status = status;
        this.body = body;
        this.errorMessage = errorMessage;
    }

    public static HttpResult of(int status, String body) {
        return new HttpResult(status, body, null);
    }

    public static HttpResult error(String message) {
        return new HttpResult(0, null, message);
    }

    public boolean isNetworkError() {
        return status == 0;
    }

    public boolean isOk() {
        return status >= 200 && status < 300;
    }

    @Override
    public String toString() {
        return "HttpResult{status=" + status + ", error=" + errorMessage + "}";
    }
}
