import com.alan.fasttransfer.core.web.WebMultipart;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * multipart 解析自测 —— 直接跑产品代码 {@link WebMultipart}。
 *
 * <p>网页上传就靠这段解析。它必须处理：二进制内容里含有类似边界的数据、
 * 边界恰好跨在两个读取块之间、多个文件、带转义的 UTF-8 文件名。
 * 这些用真机试很难覆盖，但离线可以穷举。</p>
 */
public final class WebMultipartTest {

    private static final String BOUNDARY = "----FastTransferBoundary1234567890";

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== multipart 解析 ===");

        testBoundaryOf();
        testSingleFile();
        testMultipleFiles();
        testBinaryContent();
        testContentLooksLikeBoundary();
        testFileNameEscapes();
        testChunkSplitAtBoundary();
        testChunkSplitOneByte();
        testEmptyFile();
        testMalformedNoBoundary();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 用例 ====================

    private static void testBoundaryOf() {
        check("boundary 带引号",
                BOUNDARY, WebMultipart.boundaryOf("multipart/form-data; boundary=\"" + BOUNDARY + "\""));
        check("boundary 无引号",
                BOUNDARY, WebMultipart.boundaryOf("multipart/form-data; boundary=" + BOUNDARY));
        check("boundary 后有其它参数",
                BOUNDARY, WebMultipart.boundaryOf("multipart/form-data; boundary=" + BOUNDARY + "; charset=utf-8"));
        check("无 boundary 返回 null", null, WebMultipart.boundaryOf("multipart/form-data"));
        check("contentType 为 null", null, WebMultipart.boundaryOf(null));
    }

    private static void testSingleFile() throws Exception {
        byte[] body = buildBody(new Upload("file", "hello.txt", "text/plain", "你好 World".getBytes("UTF-8")));
        Capturing handler = new Capturing();
        WebMultipart.Result result = parse(body, handler);

        check("单文件：文件数", 1, result.fileCount);
        check("单文件：文件名", "hello.txt", result.savedNames.isEmpty() ? null : result.savedNames.get(0));
        check("单文件：内容", "你好 World", handler.textOf(0));
        check("单文件：contentType", "text/plain", handler.parts.get(0).contentType);
        check("单文件：fieldName", "file", handler.parts.get(0).fieldName);
        check("单文件：size", (long) "你好 World".getBytes("UTF-8").length, handler.parts.get(0).size);
        check("单文件：全部成功收尾", 1, handler.completed);
    }

    private static void testMultipleFiles() throws Exception {
        byte[] body = buildBody(
                new Upload("f1", "a.txt", "text/plain", "AAA".getBytes("UTF-8")),
                new Upload("f2", "b.bin", "application/octet-stream", "BBB".getBytes("UTF-8")),
                new Upload("f3", "c.txt", "text/plain", "CCC".getBytes("UTF-8")));
        Capturing handler = new Capturing();
        WebMultipart.Result result = parse(body, handler);

        check("多文件：文件数", 3, result.fileCount);
        check("多文件：名字顺序", "[a.txt, b.bin, c.txt]", result.savedNames.toString());
        check("多文件：内容1", "AAA", handler.textOf(0));
        check("多文件：内容2", "BBB", handler.textOf(1));
        check("多文件：内容3", "CCC", handler.textOf(2));
        check("多文件：全部收尾", 3, handler.completed);
    }

    private static void testBinaryContent() throws Exception {
        byte[] random = new byte[300 * 1024];
        new Random(42).nextBytes(random);
        byte[] body = buildBody(new Upload("file", "blob.bin", "application/octet-stream", random));
        Capturing handler = new Capturing();
        parse(body, handler);

        check("二进制：长度一致", random.length, handler.data.get(0).length);
        check("二进制：内容一致", true, java.util.Arrays.equals(random, handler.data.get(0)));
    }

    /** 文件内容里出现「--boundary」字样的相似数据，不能误判。 */
    private static void testContentLooksLikeBoundary() throws Exception {
        byte[] tricky = ("prefix\r\n--" + BOUNDARY + "X\r\n" + "suffix").getBytes("UTF-8");
        byte[] body = buildBody(new Upload("file", "tricky.txt", "text/plain", tricky));
        Capturing handler = new Capturing();
        parse(body, handler);

        check("疑似边界：仍然只有一个文件", 1, handler.data.size());
        check("疑似边界：内容完整", true, java.util.Arrays.equals(tricky, handler.data.get(0)));
    }

    private static void testFileNameEscapes() throws Exception {
        byte[] body = buildBody(new Upload("file", "带 空格\"引号.txt", "text/plain", "x".getBytes("UTF-8")));
        Capturing handler = new Capturing();
        WebMultipart.Result result = parse(body, handler);
        check("转义文件名", "带 空格\"引号.txt",
                result.savedNames.isEmpty() ? null : result.savedNames.get(0));
    }

    /** 把请求体切成很小的块，边界必然跨块。 */
    private static void testChunkSplitAtBoundary() throws Exception {
        byte[] body = buildBody(
                new Upload("f1", "one.txt", "text/plain", "1111".getBytes("UTF-8")),
                new Upload("f2", "two.txt", "text/plain", "2222".getBytes("UTF-8")));
        for (int chunk : new int[]{3, 7, 16, 64, 333}) {
            Capturing handler = new Capturing();
            parseInChunks(body, chunk, handler);
            boolean ok = handler.data.size() == 2
                    && "1111".equals(handler.textOf(0))
                    && "2222".equals(handler.textOf(1));
            check("分块读取 chunk=" + chunk + " 结果正确", true, ok);
        }
    }

    /** 极端情况：每次只读 1 字节。 */
    private static void testChunkSplitOneByte() throws Exception {
        byte[] body = buildBody(new Upload("f", "x.txt", "text/plain", "hello".getBytes("UTF-8")));
        Capturing handler = new Capturing();
        parseInChunks(body, 1, handler);
        check("逐字节读取：内容正确", "hello", handler.textOf(0));
    }

    private static void testEmptyFile() throws Exception {
        byte[] body = buildBody(new Upload("file", "empty.txt", "text/plain", new byte[0]));
        Capturing handler = new Capturing();
        WebMultipart.Result result = parse(body, handler);
        check("空文件：文件数", 1, result.fileCount);
        check("空文件：长度 0", 0, handler.data.get(0).length);
    }

    private static void testMalformedNoBoundary() {
        try {
            WebMultipart.parse(new ByteArrayInputStream("garbage".getBytes("UTF-8")), "", new Capturing());
            check("缺少 boundary 应抛异常", "异常", "没有异常");
        } catch (IOException e) {
            check("缺少 boundary 应抛异常", "异常", "异常");
        }
    }

    // ==================== 工具 ====================

    private static final class Upload {
        final String field;
        final String name;
        final String type;
        final byte[] data;

        Upload(String field, String name, String type, byte[] data) {
            this.field = field;
            this.name = name;
            this.type = type;
            this.data = data;
        }
    }

    private static byte[] buildBody(Upload... uploads) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Upload upload : uploads) {
            out.write(("--" + BOUNDARY + "\r\n").getBytes("UTF-8"));
            // 文件名里的引号和反斜杠必须按 RFC 转义，浏览器就是这么发的。
            // 不转义的话 "a"b.txt" 会提前在第二个引号处结束，那是夹具的问题，不是解析器的问题。
            String name = upload.name.replace("\\", "\\\\").replace("\"", "\\\"");
            out.write(("Content-Disposition: form-data; name=\"" + upload.field
                    + "\"; filename=\"" + name + "\"\r\n").getBytes("UTF-8"));
            out.write(("Content-Type: " + upload.type + "\r\n\r\n").getBytes("UTF-8"));
            out.write(upload.data);
            out.write("\r\n".getBytes("UTF-8"));
        }
        out.write(("--" + BOUNDARY + "--\r\n").getBytes("UTF-8"));
        return out.toByteArray();
    }

    private static WebMultipart.Result parse(byte[] body, WebMultipart.PartHandler handler)
            throws Exception {
        return WebMultipart.parse(new ByteArrayInputStream(body), BOUNDARY, handler);
    }

    /** 按固定块大小喂数据，模拟网络分包。 */
    private static void parseInChunks(byte[] body, int chunkSize, WebMultipart.PartHandler handler)
            throws Exception {
        WebMultipart.parse(new ChunkedInputStream(body, chunkSize), BOUNDARY, handler);
    }

    /** 每次最多返回 chunkSize 字节的输入流。 */
    private static final class ChunkedInputStream extends InputStream {
        private final byte[] data;
        private final int chunkSize;
        private int position;

        ChunkedInputStream(byte[] data, int chunkSize) {
            this.data = data;
            this.chunkSize = chunkSize;
        }

        @Override
        public int read() {
            return position < data.length ? (data[position++] & 0xFF) : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (position >= data.length) {
                return -1;
            }
            int count = Math.min(Math.min(len, chunkSize), data.length - position);
            System.arraycopy(data, position, b, off, count);
            position += count;
            return count;
        }
    }

    /** 把每个 part 的内容收进内存，便于断言。 */
    private static final class Capturing implements WebMultipart.PartHandler {
        final List<WebMultipart.Part> parts = new ArrayList<>();
        final List<ByteArrayOutputStream> buffers = new ArrayList<>();
        final List<byte[]> data = new ArrayList<>();
        int completed;
        private final Set<Integer> failedParts = new HashSet<>();

        @Override
        public OutputStream onPartStart(WebMultipart.Part part) {
            parts.add(part);
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            buffers.add(buffer);
            final int index = buffers.size() - 1;
            return new OutputStream() {
                @Override
                public void write(int b) {
                    buffer.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    buffer.write(b, off, len);
                }

                @Override
                public void close() {
                    if (!failedParts.contains(index)) {
                        data.add(buffer.toByteArray());
                    }
                }
            };
        }

        @Override
        public void onPartEnd(WebMultipart.Part part, boolean success) {
            if (success) {
                completed++;
            } else {
                failedParts.add(buffers.size() - 1);
            }
        }

        String textOf(int index) {
            if (index < 0 || index >= data.size()) {
                return null;
            }
            try {
                return new String(data.get(index), "UTF-8");
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static void check(String label, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
            System.out.println("  PASS  " + label);
        } else {
            failed++;
            System.out.println("  FAIL  " + label);
            System.out.println("        expected: " + expected);
            System.out.println("        actual  : " + actual);
        }
    }
}
