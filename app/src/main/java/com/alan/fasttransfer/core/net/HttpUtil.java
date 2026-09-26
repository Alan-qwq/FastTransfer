package com.alan.fasttransfer.core.net;

import com.alan.fasttransfer.core.LocalsendProtocol;
import com.alan.fasttransfer.core.util.Logs;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 基于 {@link HttpURLConnection} 的轻量 HTTP 客户端。
 *
 * <p>使用定长流式上传，因此可以在发送过程中回报真实进度。</p>
 */
public final class HttpUtil {

    private static final String TAG = "HttpUtil";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int BUFFER_SIZE = 64 * 1024;

    /** 上传进度回调。 */
    public interface ProgressListener {
        /**
         * @param sent  已发送字节
         * @param total 总字节
         * @return false 表示请求取消
         */
        boolean onProgress(long sent, long total);
    }

    private HttpUtil() {
    }

    public static String baseUrl(String ip, int port) {
        return "http://" + ip + ":" + port;
    }

    public static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    /** 发送 JSON 请求。 */
    public static HttpResult postJson(String url, String json) {
        HttpURLConnection conn = null;
        try {
            byte[] payload = json.getBytes("UTF-8");
            conn = open(url, "POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setFixedLengthStreamingMode(payload.length);
            conn.setDoOutput(true);
            OutputStream out = new BufferedOutputStream(conn.getOutputStream(), 8 * 1024);
            out.write(payload);
            out.flush();
            out.close();
            return readResponse(conn);
        } catch (SocketTimeoutException e) {
            return HttpResult.error("timeout");
        } catch (InterruptedIOException e) {
            return HttpResult.error("cancelled");
        } catch (Throwable t) {
            return HttpResult.error(describe(t));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** GET 请求。 */
    public static HttpResult get(String url) {
        HttpURLConnection conn = null;
        try {
            conn = open(url, "GET");
            return readResponse(conn);
        } catch (SocketTimeoutException e) {
            return HttpResult.error("timeout");
        } catch (Throwable t) {
            return HttpResult.error(describe(t));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 定长流式上传。 */
    public static HttpResult postStream(String url, String contentType, long contentLength,
                                        InputStream input, ProgressListener listener) {
        HttpURLConnection conn = null;
        try {
            conn = open(url, "POST");
            conn.setRequestProperty("Content-Type", contentType == null
                    ? "application/octet-stream" : contentType);
            if (contentLength >= 0 && contentLength <= Integer.MAX_VALUE) {
                conn.setFixedLengthStreamingMode((int) contentLength);
            } else {
                conn.setFixedLengthStreamingMode(contentLength);
            }
            conn.setDoOutput(true);

            CountingOutputStream counting = new CountingOutputStream(
                    new BufferedOutputStream(conn.getOutputStream(), BUFFER_SIZE), contentLength, listener);
            try {
                byte[] buffer = new byte[BUFFER_SIZE];
                int n;
                while ((n = input.read(buffer)) > 0) {
                    counting.write(buffer, 0, n);
                }
                counting.flush();
            } finally {
                closeQuietly(counting);
            }

            if (counting.cancelled) {
                return HttpResult.error("cancelled");
            }
            return readResponse(conn);
        } catch (SocketTimeoutException e) {
            return HttpResult.error("timeout");
        } catch (InterruptedIOException e) {
            return HttpResult.error("cancelled");
        } catch (Throwable t) {
            return HttpResult.error(describe(t));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 简单 POST（无请求体，参数放在 query 上）。 */
    public static HttpResult postEmpty(String url) {
        HttpURLConnection conn = null;
        try {
            conn = open(url, "POST");
            conn.setFixedLengthStreamingMode(0);
            conn.setDoOutput(true);
            conn.getOutputStream().close();
            return readResponse(conn);
        } catch (SocketTimeoutException e) {
            return HttpResult.error("timeout");
        } catch (Throwable t) {
            return HttpResult.error(describe(t));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static String buildUrl(String ip, int port, String path, String... queryPairs) {
        StringBuilder sb = new StringBuilder(baseUrl(ip, port)).append(path);
        if (queryPairs != null && queryPairs.length > 0) {
            sb.append('?');
            for (int i = 0; i + 1 < queryPairs.length; i += 2) {
                if (i > 0) {
                    sb.append('&');
                }
                sb.append(encode(queryPairs[i])).append('=').append(encode(queryPairs[i + 1]));
            }
        }
        return sb.toString();
    }

    public static String registerUrl(String ip, int port, String pin) {
        if (pin != null && !pin.isEmpty()) {
            return buildUrl(ip, port, LocalsendProtocol.PATH_REGISTER, "pin", pin);
        }
        return buildUrl(ip, port, LocalsendProtocol.PATH_REGISTER);
    }

    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "FastTransfer/" + LocalsendProtocol.VERSION);
        conn.setRequestProperty("Connection", "close");
        return conn;
    }

    private static HttpResult readResponse(HttpURLConnection conn) {
        InputStream in = null;
        try {
            int status = conn.getResponseCode();
            in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = in == null ? "" : new String(readAll(in), "UTF-8");
            return HttpResult.of(status, body);
        } catch (SocketTimeoutException e) {
            return HttpResult.error("timeout");
        } catch (Throwable t) {
            return HttpResult.error(describe(t));
        } finally {
            closeQuietly(in);
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(16 * 1024);
        byte[] chunk = new byte[16 * 1024];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return message;
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 统计已写字节并回报进度，支持中途取消。 */
    private static class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final long total;
        private final ProgressListener listener;
        private long written;
        private long lastReport;
        private boolean cancelled;

        CountingOutputStream(OutputStream delegate, long total, ProgressListener listener) {
            this.delegate = delegate;
            this.total = total;
            this.listener = listener;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            written++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            checkCancelled();
            delegate.write(b, off, len);
            written += len;
            report(false);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            report(true);
            delegate.close();
        }

        private void checkCancelled() throws IOException {
            if (listener != null && total > 0) {
                long now = System.currentTimeMillis();
                if (now - lastReport >= 120) {
                    lastReport = now;
                    if (!listener.onProgress(written, total)) {
                        cancelled = true;
                        Logs.d(TAG, "upload cancelled by listener");
                        throw new InterruptedIOException("cancelled");
                    }
                }
            }
        }

        private void report(boolean force) {
            if (listener == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (!force && now - lastReport < 100) {
                return;
            }
            lastReport = now;
            if (!listener.onProgress(written, total)) {
                cancelled = true;
            }
        }
    }
}
