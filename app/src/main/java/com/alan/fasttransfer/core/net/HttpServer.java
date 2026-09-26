package com.alan.fasttransfer.core.net;

import com.alan.fasttransfer.core.util.Logs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 极简 HTTP/1.1 服务端：仅实现本项目所需能力
 * （POST/GET、Content-Length、chunked 请求体、Connection: close）。
 *
 * <p>不依赖任何第三方库，可在 Android 5.0+ 上运行。</p>
 */
public class HttpServer {

    private static final String TAG = "HttpServer";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_LINE = 16 * 1024;
    private static final int MAX_HEADERS = 100;
    private static final long MAX_JSON_BODY = 8L * 1024 * 1024;
    private static final int SOCKET_READ_TIMEOUT_MS = 300_000;

    /** 请求回调。 */
    public interface Handler {
        Response handle(Request request) throws IOException;
    }

    /** 一次请求。 */
    public static class Request {
        public String method = "";
        public String path = "";
        public String rawQuery = "";
        public String httpVersion = "HTTP/1.1";
        public final Map<String, String> headers = new HashMap<>();
        public InputStream body;
        public String clientIp = "";
        public int clientPort;

        public Map<String, String> query() {
            return parseQuery(rawQuery);
        }

        public String query(String key) {
            return query().get(key);
        }

        public long contentLength() {
            String v = headers.get("content-length");
            if (v == null) {
                return -1L;
            }
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return -1L;
            }
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public byte[] readBody() throws IOException {
            long len = contentLength();
            if (len < 0 || len > MAX_JSON_BODY) {
                throw new IOException("body too large or unknown length");
            }
            return readFully(body, (int) len);
        }
    }

    /** 一次响应。 */
    public static class Response {
        public int status = 200;
        public String contentType = "application/json; charset=utf-8";
        public byte[] body = new byte[0];

        public static Response json(int status, String json) {
            Response r = new Response();
            r.status = status;
            r.contentType = "application/json; charset=utf-8";
            r.body = json == null ? new byte[0] : json.getBytes(UTF8);
            return r;
        }

        public static Response empty(int status) {
            Response r = new Response();
            r.status = status;
            return r;
        }

        public static Response message(int status, String message) {
            String json = "{\"message\":\"" + escape(message) + "\"}";
            return json(status, json);
        }

        /** 流式响应的内容长度；-1 表示未知（用 Connection: close 界定）。 */
        public long contentLength = -1L;

        /** 由业务层直接写裸数据（例如文件下载）。 */
        public static Response stream(int status, String contentType, StreamWriter writer) {
            Response r = new Response();
            r.status = status;
            r.contentType = contentType;
            r.writer = writer;
            return r;
        }

        /** 流式响应 + 声明长度（浏览器可显示下载进度）。 */
        public static Response stream(int status, String contentType, long contentLength,
                                      StreamWriter writer) {
            Response r = stream(status, contentType, writer);
            r.contentLength = contentLength;
            return r;
        }

        public Response header(String name, String value) {
            if (name != null && value != null) {
                extraHeaders.add(name + ": " + value);
            }
            return this;
        }

        StreamWriter writer;
        final java.util.List<String> extraHeaders = new java.util.ArrayList<>();
    }

    /** 流式响应写入器。 */
    public interface StreamWriter {
        void writeTo(OutputStream out) throws IOException;
    }

    private final Handler handler;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private final ThreadPoolExecutor workers;
    private final Object writeLock = new Object();

    public HttpServer(Handler handler) {
        this.handler = handler;
        this.workers = new ThreadPoolExecutor(
                2, 48, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                new ThreadFactory() {
                    private final AtomicInteger seq = new AtomicInteger();

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "http-worker-" + seq.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.workers.allowCoreThreadTimeOut(true);
    }

    public boolean isRunning() {
        return running;
    }

    /** 启动监听，返回是否成功。 */
    public synchronized boolean start(int port) {
        if (running) {
            return true;
        }
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(port), 64);
            serverSocket = ss;
        } catch (IOException e) {
            Logs.e(TAG, "bind port " + port + " failed: " + e.getMessage());
            return false;
        }
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "http-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Logs.d(TAG, "listening on " + port);
        return true;
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        workers.shutdownNow();
    }

    public int getPort() {
        ServerSocket ss = serverSocket;
        return ss == null ? -1 : ss.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
                try {
                    workers.execute(new Runnable() {
                        @Override
                        public void run() {
                            handleClient(socket);
                        }
                    });
                } catch (RejectedExecutionException ree) {
                    closeQuietly(socket);
                }
            } catch (IOException e) {
                if (running) {
                    Logs.w(TAG, "accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        Request request = new Request();
        try {
            request.clientIp = socket.getInetAddress() == null
                    ? "" : socket.getInetAddress().getHostAddress();
            request.clientPort = socket.getPort();

            BufferedInputStream in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);

            if (!readHead(in, request)) {
                return;
            }

            // 浏览器上传大文件（例如网页传输的 multipart）前会先发
            // "Expect: 100-continue" 并**等我们回应才肯发 body**。
            // 不回的话它要干等一秒才自己开始发，传大文件时表现成「卡住不动」；
            // 所以必须先回一个空的 100 Continue 再往下走。
            if (expectsContinue(request)) {
                out.write(CONTINUE_RESPONSE);
                out.flush();
            }

            Response response;
            try {
                response = handler.handle(request);
            } catch (SocketException e) {
                throw e;
            } catch (Throwable t) {
                Logs.e(TAG, "handler error " + request.method + " " + request.path, t);
                response = Response.message(500, "Internal error");
            }
            if (response == null) {
                response = Response.empty(200);
            }
            writeResponse(out, request, response);
        } catch (IOException e) {
            Logs.d(TAG, "connection closed: " + e.getMessage());
        } catch (Throwable t) {
            Logs.e(TAG, "unexpected", t);
        } finally {
            closeQuietly(socket);
        }
    }

    private boolean readHead(BufferedInputStream in, Request request) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            return false;
        }
        int sp1 = requestLine.indexOf(' ');
        int sp2 = requestLine.lastIndexOf(' ');
        if (sp1 <= 0 || sp2 <= sp1) {
            throw new IOException("bad request line: " + requestLine);
        }
        request.method = requestLine.substring(0, sp1).toUpperCase(Locale.ROOT);
        String target = requestLine.substring(sp1 + 1, sp2);
        request.httpVersion = requestLine.substring(sp2 + 1);

        int q = target.indexOf('?');
        if (q >= 0) {
            request.path = target.substring(0, q);
            request.rawQuery = target.substring(q + 1);
        } else {
            request.path = target;
        }

        int headerCount = 0;
        String line;
        while ((line = readLine(in)) != null) {
            if (line.isEmpty()) {
                break;
            }
            if (++headerCount > MAX_HEADERS) {
                throw new IOException("too many headers");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (!request.headers.containsKey(name)) {
                request.headers.put(name, value);
            }
        }

        request.body = new BodyStream(in, request);
        return true;
    }

    /** Content-Length / chunked 请求体。 */
    private static class BodyStream extends InputStream {
        private final BufferedInputStream in;
        private final boolean chunked;
        private long remaining;
        private long chunkRemaining;
        private boolean finished;

        BodyStream(BufferedInputStream in, Request request) {
            this.in = in;
            String te = request.header("transfer-encoding");
            this.chunked = te != null && te.toLowerCase(Locale.ROOT).contains("chunked");
            long len = request.contentLength();
            this.remaining = len < 0 ? 0 : len;
            this.chunkRemaining = -1;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (chunked) {
                if (finished) {
                    return -1;
                }
                if (chunkRemaining <= 0) {
                    if (!nextChunk()) {
                        return -1;
                    }
                }
                int toRead = (int) Math.min(len, chunkRemaining);
                int n = in.read(b, off, toRead);
                if (n < 0) {
                    finished = true;
                    return -1;
                }
                chunkRemaining -= n;
                if (chunkRemaining == 0) {
                    readLine(in); // 块尾 CRLF
                }
                return n;
            }

            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int n = in.read(b, off, toRead);
            if (n < 0) {
                finished = true;
                return -1;
            }
            remaining -= n;
            return n;
        }

        private boolean nextChunk() throws IOException {
            String line = readLine(in);
            if (line == null) {
                finished = true;
                return false;
            }
            int semi = line.indexOf(';');
            if (semi >= 0) {
                line = line.substring(0, semi);
            }
            long size;
            try {
                size = Long.parseLong(line.trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size: " + line);
            }
            if (size <= 0) {
                finished = true;
                return false;
            }
            chunkRemaining = size;
            return true;
        }

        @Override
        public int available() {
            return 0;
        }
    }

    private static final byte[] CRLF = {'\r', '\n'};

    /** 对 {@code Expect: 100-continue} 的空回应，告诉浏览器可以发 body 了。 */
    private static final byte[] CONTINUE_RESPONSE =
            "HTTP/1.1 100 Continue\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    private static boolean expectsContinue(Request request) {
        String expect = request.header("Expect");
        return expect != null && expect.toLowerCase(Locale.ROOT).contains("100-continue");
    }

    private void writeResponse(OutputStream out, Request request, Response response) throws IOException {
        byte[] body = response.body == null ? new byte[0] : response.body;
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n");
        head.append("Content-Type: ").append(response.contentType).append("\r\n");
        // 流式 + 已知长度时也报 Content-Length，浏览器才能显示下载进度
        if (response.writer == null || response.contentLength >= 0) {
            head.append("Content-Length: ")
                    .append(response.writer == null ? body.length : response.contentLength)
                    .append("\r\n");
        }
        for (String extra : response.extraHeaders) {
            head.append(extra).append("\r\n");
        }
        head.append("Connection: close\r\n");
        head.append("Server: FastTransfer\r\n");
        head.append("\r\n");

        synchronized (writeLock) {
            out.write(head.toString().getBytes(UTF8));
            if (response.writer != null) {
                response.writer.writeTo(out);
            } else if (body.length > 0) {
                out.write(body);
            }
            out.flush();
        }
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 422: return "Unprocessable Entity";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            default: return "Status";
        }
    }

    /** 读取一行（CRLF 或 LF 结尾），超长抛异常。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') {
                break;
            }
            if (c == '\r') {
                continue;
            }
            if (buffer.size() >= MAX_LINE) {
                throw new IOException("line too long");
            }
            buffer.write(c);
        }
        if (!any && c == -1) {
            return null;
        }
        return new String(buffer.toByteArray(), UTF8);
    }

    public static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(data, read, length - read);
            if (n < 0) {
                throw new EOFException("unexpected end of stream: " + read + "/" + length);
            }
            read += n;
        }
        return data;
    }

    public static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return map;
        }
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) {
                    map.put(decode(pair), "");
                } else {
                    map.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
                }
            } catch (IllegalArgumentException ignored) {
                // 忽略非法编码的键值对
            }
        }
        return map;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    public static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static String localAddress(Socket socket) {
        InetAddress address = socket.getLocalAddress();
        return address == null ? "" : address.getHostAddress();
    }
}
