package com.alan.fasttransfer.test;

import com.alan.fasttransfer.core.LocalsendProtocol;
import com.alan.fasttransfer.core.dto.DeviceInfoDto;
import com.alan.fasttransfer.core.dto.FileDto;
import com.alan.fasttransfer.core.dto.PrepareUploadRequestDto;
import com.alan.fasttransfer.core.dto.PrepareUploadResponseDto;
import com.alan.fasttransfer.core.net.HttpResult;
import com.alan.fasttransfer.core.net.HttpServer;
import com.alan.fasttransfer.core.net.HttpUtil;
import com.alan.fasttransfer.core.util.Json;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 纯 JVM 协议自测：
 * 直接跑真实的 {@link HttpServer} 与 {@link HttpUtil}，
 * 覆盖请求解析、chunked 请求体、JSON 往返、query 解码与上传进度。
 */
public final class ProtocolTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== FastTransfer protocol self-test ===");

        testQueryParsing();
        testJsonRoundTrip();
        testHttpServerAndClient();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static void check(boolean condition, String name, String detail) {
        if (condition) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name + "  -> " + detail);
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("[" + title + "]");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- 用例

    private static void testQueryParsing() {
        section("query parsing");
        Map<String, String> query = HttpServer.parseQuery(
                "sessionId=abc&fileId=some%20file&token=a%2Bb+c&flag");
        check("abc".equals(query.get("sessionId")), "sessionId decoded",
                String.valueOf(query.get("sessionId")));
        check("some file".equals(query.get("fileId")), "fileId percent-decoded",
                String.valueOf(query.get("fileId")));
        check("a+b c".equals(query.get("token")), "token plus-space decoded",
                String.valueOf(query.get("token")));
        check("".equals(query.get("flag")), "valueless flag", String.valueOf(query.get("flag")));
        check(HttpServer.parseQuery(null).isEmpty(), "null query", "not empty");
        Map<String, String> invalid = HttpServer.parseQuery("%zz=%4");
        check(invalid.size() == 1 && "%4".equals(invalid.get("%zz")),
                "invalid percent kept verbatim", String.valueOf(invalid));
    }

    private static void testJsonRoundTrip() {
        section("json round trip");

        DeviceInfoDto info = DeviceInfoDto.of("测试设备 A", "Xiaomi 14", "mobile", "fp-123", 53317);
        String infoJson = Json.toJson(info);
        check(infoJson.contains("\"deviceModel\":\"Xiaomi 14\""), "camelCase serialization", infoJson);
        DeviceInfoDto parsed = Json.fromJson(infoJson, DeviceInfoDto.class);
        check(parsed != null && "测试设备 A".equals(parsed.alias), "alias survives round trip",
                infoJson);
        check(parsed != null && parsed.port == 53317, "port survives round trip", infoJson);

        PrepareUploadRequestDto request = new PrepareUploadRequestDto();
        request.info = info;
        FileDto file = new FileDto("file-1", "photo.jpg", 2048, "image/jpeg");
        request.files.put(file.id, file);

        String requestJson = Json.toJson(request);
        PrepareUploadRequestDto back = Json.fromJson(requestJson, PrepareUploadRequestDto.class);
        check(back != null && back.files != null && back.files.size() == 1, "files map parsed",
                requestJson);
        check(back != null && back.files.get("file-1") != null
                        && "photo.jpg".equals(back.files.get("file-1").fileName),
                "file name parsed", requestJson);
        check(back != null && back.info != null && "fp-123".equals(back.info.fingerprint()),
                "fingerprint parsed", requestJson);

        PrepareUploadResponseDto response = new PrepareUploadResponseDto();
        response.sessionId = "session-1";
        response.files.put("file-1", "token-1");
        PrepareUploadResponseDto responseBack = Json.fromJson(Json.toJson(response),
                PrepareUploadResponseDto.class);
        check(responseBack != null && "session-1".equals(responseBack.sessionId)
                        && "token-1".equals(responseBack.files.get("file-1")),
                "prepare-upload response parsed", Json.toJson(response));

        check("desktop".equals(LocalsendProtocol.normalizeDeviceType("fridge")),
                "unknown deviceType falls back to desktop",
                String.valueOf(LocalsendProtocol.normalizeDeviceType("fridge")));
        check(LocalsendProtocol.DEVICE_TYPE_SERVER.equals(
                        LocalsendProtocol.normalizeDeviceType("server")),
                "known deviceType kept",
                String.valueOf(LocalsendProtocol.normalizeDeviceType("server")));
    }

    private static void testHttpServerAndClient() throws Exception {
        section("http server + client");

        final Map<String, byte[]> uploads = new HashMap<>();
        final Map<String, String> uploadQueries = new HashMap<>();

        HttpServer server = new HttpServer(new HttpServer.Handler() {
            @Override
            public HttpServer.Response handle(HttpServer.Request request) throws IOException {
                if (LocalsendProtocol.PATH_REGISTER.equals(request.path)) {
                    byte[] body = request.readBody();
                    DeviceInfoDto sender = Json.fromJson(new String(body, "UTF-8"),
                            DeviceInfoDto.class);
                    DeviceInfoDto mine = DeviceInfoDto.of("receiver", "Android", "mobile", "fp-r", 53317);
                    return HttpServer.Response.json(200, Json.toJson(mine) + "\n// from="
                            + (sender == null ? "?" : sender.alias));
                }
                if (LocalsendProtocol.PATH_INFO.equals(request.path)) {
                    return HttpServer.Response.json(200,
                            Json.toJson(DeviceInfoDto.of("receiver", "Android", "mobile", "fp-r", 53317)));
                }
                if (LocalsendProtocol.PATH_PREPARE_UPLOAD.equals(request.path)) {
                    byte[] body = request.readBody();
                    PrepareUploadRequestDto payload = Json.fromJson(new String(body, "UTF-8"),
                            PrepareUploadRequestDto.class);
                    PrepareUploadResponseDto response = new PrepareUploadResponseDto();
                    response.sessionId = "session-abc";
                    for (String id : payload.files.keySet()) {
                        response.files.put(id, "token-" + id);
                    }
                    return HttpServer.Response.json(200, Json.toJson(response));
                }
                if (LocalsendProtocol.PATH_UPLOAD.equals(request.path)) {
                    Map<String, String> query = request.query();
                    byte[] data = HttpUtil.readAll(request.body);
                    uploads.put(query.get("fileId"), data);
                    uploadQueries.put(query.get("fileId"), request.rawQuery);
                    return HttpServer.Response.empty(200);
                }
                if (LocalsendProtocol.PATH_CANCEL.equals(request.path)) {
                    return HttpServer.Response.empty(200);
                }
                return HttpServer.Response.message(404, "Not found");
            }
        });

        int port = 45999;
        boolean started = server.start(port);
        check(started, "server binds port", "failed to bind " + port);
        if (!started) {
            return;
        }
        try {
            String base = "http://127.0.0.1:" + port;

            // 1) register
            DeviceInfoDto mine = DeviceInfoDto.of("sender", "PC", "desktop", "fp-s", 53317);
            HttpResult register = HttpUtil.postJson(
                    HttpUtil.buildUrl("127.0.0.1", port, LocalsendProtocol.PATH_REGISTER),
                    Json.toJson(mine));
            check(register.status == 200, "register returns 200", register.toString());
            check(register.body != null && register.body.contains("fp-r"),
                    "register response body parsed", register.body);
            check(register.body != null && register.body.contains("from=sender"),
                    "server read our json body", register.body);

            // 2) info
            HttpResult info = HttpUtil.get(
                    HttpUtil.buildUrl("127.0.0.1", port, LocalsendProtocol.PATH_INFO));
            check(info.status == 200, "info returns 200", info.toString());

            // 3) prepare-upload
            PrepareUploadRequestDto request = new PrepareUploadRequestDto();
            request.info = mine;
            FileDto file = new FileDto("f1", "demo.bin", 4096, "application/octet-stream");
            request.files.put(file.id, file);
            HttpResult prepare = HttpUtil.postJson(
                    HttpUtil.buildUrl("127.0.0.1", port, LocalsendProtocol.PATH_PREPARE_UPLOAD),
                    Json.toJson(request));
            check(prepare.status == 200, "prepare-upload returns 200", prepare.toString());
            PrepareUploadResponseDto response = Json.fromJson(prepare.body,
                    PrepareUploadResponseDto.class);
            check(response != null && "session-abc".equals(response.sessionId),
                    "session id parsed", prepare.body);
            check(response != null && "token-f1".equals(response.files.get("f1")),
                    "per-file token parsed", prepare.body);

            // 4) upload with Content-Length（含真实进度回调）
            byte[] payload = randomBytes(4096);
            final long[] progressMax = {0};
            final int[] progressCalls = {0};
            String uploadUrl = HttpUtil.buildUrl("127.0.0.1", port, LocalsendProtocol.PATH_UPLOAD,
                    "sessionId", "session-abc", "fileId", "f1", "token", "token-f1");
            HttpResult upload = HttpUtil.postStream(uploadUrl, "application/octet-stream",
                    payload.length, new ByteArrayInputStream(payload),
                    new HttpUtil.ProgressListener() {
                        @Override
                        public boolean onProgress(long sent, long total) {
                            progressCalls[0]++;
                            progressMax[0] = Math.max(progressMax[0], sent);
                            return true;
                        }
                    });
            check(upload.status == 200, "upload returns 200", upload.toString());
            check(sha256(payload).equals(sha256(uploads.get("f1"))),
                    "uploaded bytes identical", "payload mismatch");
            check(progressCalls[0] > 0, "progress callback fired",
                    "calls=" + progressCalls[0]);
            check(progressMax[0] == payload.length, "progress reaches total",
                    progressMax[0] + "/" + payload.length);

            // 5) 取消
            HttpResult cancel = HttpUtil.postEmpty(HttpUtil.buildUrl("127.0.0.1", port,
                    LocalsendProtocol.PATH_CANCEL, "sessionId", "session-abc"));
            check(cancel.status == 200, "cancel returns 200", cancel.toString());

            // 6) 未知路径 404
            HttpResult notFound = HttpUtil.get(base + "/nope");
            check(notFound.status == 404, "unknown path returns 404", notFound.toString());

            // 7) chunked 请求体（模拟第三方客户端）
            byte[] chunked = randomBytes(5000);
            uploads.clear();
            int chunkedStatus = rawChunkedUpload(port, chunked);
            check(chunkedStatus == 200, "chunked upload returns 200",
                    "status=" + chunkedStatus);
            check(sha256(chunked).equals(sha256(uploads.get("chunked"))),
                    "chunked bytes identical", "payload mismatch");

            // 8) query 编码往返
            String decodedId = "文件 名+b&c=d.txt";
            uploads.clear();
            HttpResult encodedUpload = HttpUtil.postStream(
                    HttpUtil.buildUrl("127.0.0.1", port, LocalsendProtocol.PATH_UPLOAD,
                            "sessionId", "s", "fileId", decodedId, "token", "t"),
                    "text/plain", 3, new ByteArrayInputStream("abc".getBytes("UTF-8")), null);
            check(encodedUpload.status == 200, "upload with encoded query returns 200",
                    encodedUpload.toString());
            check(uploads.containsKey(decodedId), "query value decoded by server",
                    "keys=" + uploads.keySet());

            // 9) Expect: 100-continue
            // 浏览器上传大文件前会先发这个头并等服务器回应才肯发 body。
            // 服务器不回的话，真实场景里表现成「点了接收之后卡住不动」。
            uploads.clear();
            byte[] expectPayload = randomBytes(2048);
            String expectResult = rawExpectContinue(port, "expect-1", expectPayload);
            check("100 CONTINUE".equals(expectResult),
                    "responds 100 Continue before body", "got: " + expectResult);
            check(sha256(expectPayload).equals(sha256(uploads.get("expect-1"))),
                    "body accepted after 100 Continue", "payload mismatch");
        } finally {
            server.stop();
            sleep(50);
            check(!server.isRunning(), "server stops", "still running");
        }
    }

    /**
     * 模拟浏览器发 {@code Expect: 100-continue} 的上传。
     *
     * <p>关键点：body 必须在**收到 100 Continue 之后**才发。
     * 如果服务器不回 100，这里会读超时，测试就能抓到。</p>
     *
     * @return "100 CONTINUE" 表示服务器按规范先回了 100；否则返回实际收到的东西
     */
    private static String rawExpectContinue(int port, String fileId, byte[] payload)
            throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        try {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            String head = "POST " + LocalsendProtocol.PATH_UPLOAD
                    + "?sessionId=s&fileId=" + fileId + "&token=t HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Content-Length: " + payload.length + "\r\n"
                    + "Expect: 100-continue\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            out.flush();

            // 先读一行：规范要求这里必须是 "HTTP/1.1 100 Continue"
            InputStream in = socket.getInputStream();
            String firstLine = readLine(in);
            if (firstLine == null || firstLine.indexOf("100") < 0) {
                return String.valueOf(firstLine);
            }

            // 收到 100 了，现在才发 body
            out.write(payload);
            out.flush();

            // 再读最终响应行
            String statusLine = readLine(in);
            while (statusLine != null && statusLine.isEmpty()) {
                statusLine = readLine(in);
            }
            if (statusLine != null && statusLine.indexOf("200") >= 0
                    && firstLine.indexOf("100") >= 0) {
                return "100 CONTINUE";
            }
            return "first=" + firstLine + " final=" + statusLine;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        return c < 0 && sb.length() == 0 ? null : sb.toString();
    }

    private static int rawChunkedUpload(int port, byte[] payload) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        try {
            socket.setSoTimeout(15000);
            OutputStream out = socket.getOutputStream();
            String head = "POST " + LocalsendProtocol.PATH_UPLOAD
                    + "?sessionId=s&fileId=chunked&token=t HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            int chunkSize = 1000;
            for (int offset = 0; offset < payload.length; offset += chunkSize) {
                int length = Math.min(chunkSize, payload.length - offset);
                out.write((Integer.toHexString(length) + "\r\n").getBytes("UTF-8"));
                out.write(payload, offset, length);
                out.write("\r\n".getBytes("UTF-8"));
            }
            out.write("0\r\n\r\n".getBytes("UTF-8"));
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[4096];
            StringBuilder response = new StringBuilder();
            int read;
            while ((read = in.read(buffer)) > 0) {
                response.append(new String(buffer, 0, read, "UTF-8"));
                if (response.indexOf("\r\n\r\n") >= 0) {
                    break;
                }
            }
            String text = response.toString();
            int space = text.indexOf(' ');
            if (space < 0) {
                return -1;
            }
            int end = text.indexOf(' ', space + 1);
            return Integer.parseInt(text.substring(space + 1, end < 0 ? text.length() : end).trim());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        return data;
    }

    private static String sha256(byte[] data) {
        if (data == null) {
            return "null";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "error";
        }
    }
}
