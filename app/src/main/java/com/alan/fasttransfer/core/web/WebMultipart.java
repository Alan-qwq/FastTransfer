package com.alan.fasttransfer.core.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 极简流式 multipart/form-data 解析（纯 Java，便于离线测试）。
 *
 * <p>只做网页上传真正需要的那一件事：按 boundary 切出各个 part，
 * 把文件内容**直接流式写进目标流**，不在内存里攒整份文件 ——
 * 从电脑浏览器传几百 MB 的文件是常态。</p>
 *
 * <p>对外两个事件，顺序确定：{@code onBoundary(headers, last)} 与
 * {@code onData(bytes, off, len)}。</p>
 */
public final class WebMultipart {

    /** 单个 part 的长度上限，防止恶意请求把手机存储写爆。 */
    public static final long MAX_PART_BYTES = 2L * 1024 * 1024 * 1024;

    private static final int READ_BUFFER = 16 * 1024;

    private WebMultipart() {
    }

    /** 一次上传里的一个 part。 */
    public static final class Part {
        public String fieldName = "";
        public String fileName = "";
        public String contentType = "application/octet-stream";
        public long size;

        public boolean hasFile() {
            return fileName != null && !fileName.isEmpty();
        }
    }

    /** 逐 part 回调。 */
    public interface PartHandler {
        /**
         * 遇到一个带文件的 part。
         *
         * @return 写入目标；返回 null 表示跳过该 part
         */
        OutputStream onPartStart(Part part) throws IOException;

        /** 该 part 结束。 */
        void onPartEnd(Part part, boolean success) throws IOException;
    }

    /** 解析结果统计。 */
    public static final class Result {
        public int fileCount;
        public long totalBytes;
        public final List<String> savedNames = new ArrayList<>();
    }

    // ==================== boundary ====================

    /** 从 Content-Type 头里取出 boundary。 */
    public static String boundaryOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        int index = lower.indexOf("boundary=");
        if (index < 0) {
            return null;
        }
        String value = contentType.substring(index + "boundary=".length()).trim();
        if (value.startsWith("\"")) {
            int end = value.indexOf('"', 1);
            value = end > 0 ? value.substring(1, end) : value.substring(1);
        } else {
            int semi = value.indexOf(';');
            if (semi >= 0) {
                value = value.substring(0, semi);
            }
            value = value.trim();
        }
        return value.isEmpty() ? null : value;
    }

    // ==================== 主解析流程 ====================

    /** 解析 multipart 正文。 */
    public static Result parse(InputStream in, String boundary, PartHandler handler)
            throws IOException {
        if (boundary == null || boundary.isEmpty()) {
            throw new IOException("no boundary");
        }
        if (handler == null) {
            throw new IOException("no handler");
        }

        byte[] delimiter = ("--" + boundary).getBytes("UTF-8");
        final Result result = new Result();
        final Part[] current = new Part[1];
        final OutputStream[] target = new OutputStream[1];
        final long[] written = new long[1];

        Scanner scanner = new Scanner(delimiter, new Scanner.Listener() {
            @Override
            public void onBoundary(byte[] headers, boolean last) throws IOException {
                if (current[0] != null) {
                    flushAndClose(target[0]);
                    handler.onPartEnd(current[0], true);
                    current[0] = null;
                    target[0] = null;
                    written[0] = 0;
                }
                if (last) {
                    return;
                }
                Part part = new Part();
                parseHeaders(headers, part);
                current[0] = part;
                if (part.hasFile()) {
                    result.fileCount++;
                    result.savedNames.add(part.fileName);
                    target[0] = handler.onPartStart(part);
                }
            }

            @Override
            public void onData(byte[] data, int offset, int length) throws IOException {
                if (current[0] == null || target[0] == null || length <= 0) {
                    return;
                }
                target[0].write(data, offset, length);
                written[0] += length;
                current[0].size = written[0];
                result.totalBytes += length;
                if (written[0] > MAX_PART_BYTES) {
                    throw new IOException("part too large");
                }
            }
        });

        byte[] buffer = new byte[READ_BUFFER];
        int read;
        while (!scanner.isFinished() && (read = in.read(buffer)) > 0) {
            scanner.feed(buffer, 0, read);
        }
        scanner.finish();

        if (current[0] != null) {
            flushAndClose(target[0]);
            handler.onPartEnd(current[0], true);
        }
        return result;
    }

    private static void flushAndClose(OutputStream target) {
        if (target == null) {
            return;
        }
        try {
            target.flush();
        } catch (Throwable ignored) {
        }
        try {
            target.close();
        } catch (Throwable ignored) {
        }
    }

    // ==================== part 头部 ====================

    /** 解析 part 头部，取出 name / filename / content-type。 */
    static void parseHeaders(byte[] head, Part part) {
        if (head == null || head.length == 0) {
            return;
        }
        String text;
        try {
            text = new String(head, "UTF-8");
        } catch (Exception e) {
            return;
        }
        for (String line : text.split("\r\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-disposition:")) {
                String name = extractValue(line, "name=");
                String fileName = extractValue(line, "filename=");
                if (name != null) {
                    part.fieldName = name;
                }
                if (fileName != null) {
                    part.fileName = fileName;
                }
            } else if (lower.startsWith("content-type:")) {
                String value = line.substring(line.indexOf(':') + 1).trim();
                if (!value.isEmpty()) {
                    part.contentType = value;
                }
            }
        }
    }

    /** 取 {@code key="value"} 里的 value；兼容无引号与转义引号。 */
    static String extractValue(String line, String key) {
        int index = line.toLowerCase(Locale.ROOT).indexOf(key);
        if (index < 0) {
            return null;
        }
        String rest = line.substring(index + key.length()).trim();
        if (rest.startsWith("\"")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < rest.length(); i++) {
                char c = rest.charAt(i);
                // 反斜杠转义：\" 是一个真的引号，\\ 是一个真的反斜杠，
                // 其它组合按 RFC 保留反斜杠本身（浏览器很少这么发，但别吃掉数据）。
                if (c == '\\' && i + 1 < rest.length()) {
                    char next = rest.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        sb.append(next);
                        i++;
                        continue;
                    }
                    sb.append(c);
                    continue;
                }
                if (c == '"') {
                    return sb.toString();
                }
                sb.append(c);
            }
            return sb.toString();
        }
        int semi = rest.indexOf(';');
        return (semi >= 0 ? rest.substring(0, semi) : rest).trim();
    }

    // ==================== 边界扫描 ====================

    /**
     * 在字节流里按 {@code --boundary} 切分。
     *
     * <p>位置量只有一个原则：{@code pos} 之前的数据<b>要么已经交给下游、
     * 要么已经确认可以丢弃</b>。凡是不确定的东西一律留在 {@code buffer} 里，
     * 靠 {@code process()} 反复重扫来推进。</p>
     *
     * <p>几条踩过坑才定下来的规矩：</p>
     * <ul>
     *   <li>找到 {@code --boundary} 不代表它是分隔符：后面必须跟 {@code \r\n}
     *       或 {@code --}。像 {@code --boundaryX} 这种只是正文数据，
     *       要跳过它接着找（否则文件会在第一个假边界处被截断）；</li>
     *   <li>候选边界的收尾还没到齐时，边界之前的字节已经 100% 是正文，
     *       可以先发出去，但 {@code pos} 只能推到<b>边界起点</b>，
     *       这样下一轮还能重新看到这个边界；</li>
     *   <li>{@code compact()} 的丢弃基准是 {@code pos}，而 {@code pos}
     *       永远不会越过尚未发出的正文，所以不会丢数据。</li>
     * </ul>
     */
    static final class Scanner {

        interface Listener {
            void onBoundary(byte[] headers, boolean last) throws IOException;

            void onData(byte[] data, int offset, int length) throws IOException;
        }

        private static final byte[] EMPTY = new byte[0];

        private static final int MODE_BODY = 0;
        private static final int MODE_HEADERS = 1;

        private final byte[] pattern;
        private final Listener listener;

        /** 尚未处理完的数据。只会随着发送/丢弃而收缩。 */
        private byte[] buffer = EMPTY;
        /** 已经交给下游的正文/头部位置；buffer 的 [0, emitted) 可以丢。 */
        private int emitted;
        /** 下一次搜索边界/空行的起点。 */
        private int searchFrom;
        /** 边界行结束的位置（进入 MODE_HEADERS 后有效）。 */
        private int delimEnd;
        private int mode = MODE_BODY;
        private boolean inPart;
        private boolean finished;

        Scanner(byte[] delimiter, Listener listener) {
            this.pattern = delimiter;
            this.listener = listener;
        }

        boolean isFinished() {
            return finished;
        }

        void feed(byte[] data, int offset, int length) throws IOException {
            if (length <= 0) {
                return;
            }
            byte[] combined = new byte[buffer.length + length];
            System.arraycopy(buffer, 0, combined, 0, buffer.length);
            System.arraycopy(data, offset, combined, buffer.length, length);
            buffer = combined;
            if (!finished) {
                process();
            }
            compact();
        }

        /** 输入结束：把剩下的当正文交出去，不静默吞掉用户数据。 */
        void finish() throws IOException {
            if (finished) {
                return;
            }
            finished = true;
            if (inPart && buffer.length > emitted) {
                listener.onData(buffer, emitted, buffer.length - emitted);
            }
            emitted = buffer.length;
            searchFrom = buffer.length;
        }

        private void process() throws IOException {
            while (!finished) {
                if (mode == MODE_HEADERS) {
                    int headEnd = indexOfDoubleCrlf(buffer, searchFrom, buffer.length);
                    if (headEnd < 0) {
                        // 头部还没到齐。已经把 [0, delimEnd) 之前的都处理完了，
                        // 但头部的起点必须留着，不能丢。
                        searchFrom = Math.max(searchFrom, buffer.length - 3);
                        return;
                    }
                    int end = headEnd + 4;
                    byte[] headers = new byte[end - delimEnd];
                    System.arraycopy(buffer, delimEnd, headers, 0, headers.length);
                    inPart = true;
                    mode = MODE_BODY;
                    emitted = end;
                    searchFrom = end;
                    listener.onBoundary(headers, false);
                    compact();
                    continue;
                }

                int hit = indexOf(buffer, pattern, searchFrom);
                if (hit < 0) {
                    // 没有边界：把安全的部分发出去。
                    // 必须压住「一个完整分隔符 + 它前面的 CRLF」，
                    // 否则正文最后那个 CRLF 会先被当正文发掉，之后再也没法剥。
                    if (inPart) {
                        emitUpTo(Math.max(emitted, buffer.length - (pattern.length + 2)));
                    }
                    return;
                }

                int after = hit + pattern.length;
                if (after + 1 >= buffer.length) {
                    // 分隔符的收尾两字节还没到齐，先把边界之前的安全正文发掉，
                    // 边界本身留在缓冲里等下一块（pos 不动，下一轮重新看到它）。
                    emitUpTo(Math.max(emitted, hit - 2));
                    return;
                }

                if (buffer[after] == '-' && buffer[after + 1] == '-') {
                    emitBodyBefore(hit);
                    finished = true;
                    listener.onBoundary(EMPTY, true);
                    return;
                }
                if (buffer[after] == '\r' && buffer[after + 1] == '\n') {
                    emitBodyBefore(hit);
                    delimEnd = after + 2;
                    searchFrom = delimEnd;
                    mode = MODE_HEADERS;
                    continue;
                }
                if (buffer[after] == '\n') {
                    emitBodyBefore(hit);
                    delimEnd = after + 1;
                    searchFrom = delimEnd;
                    mode = MODE_HEADERS;
                    continue;
                }
                // 后面既不是 CRLF 也不是 "--"：正文里长得像边界的数据，跳过它继续找。
                searchFrom = hit + 1;
            }
        }

        /**
         * 把 {@code [emitted, end)} 作为正文发出去。
         *
         * <p>调用方保证 {@code end} 不会越过真正的边界，所以这里不做 CRLF 处理。</p>
         */
        private void emitUpTo(int end) throws IOException {
            if (end > emitted) {
                listener.onData(buffer, emitted, end - emitted);
                emitted = end;
            }
        }

        /**
         * 发送边界之前的正文，并剥掉紧贴边界的那个 CRLF。
         *
         * <p>因为发送时始终压住了 {@code pattern.length + 2} 字节，
         * 这个 CRLF 一定还在缓冲里、还没被发出去。</p>
         */
        private void emitBodyBefore(int boundaryStart) throws IOException {
            if (!inPart) {
                // 第一个边界之前是 preamble，直接丢弃
                emitted = Math.max(emitted, boundaryStart);
                return;
            }
            int end = boundaryStart;
            if (end - 2 >= emitted
                    && buffer[end - 2] == '\r' && buffer[end - 1] == '\n') {
                end -= 2;
            }
            emitUpTo(end);
            // CRLF 已经在缓冲里被跳过，记账要跟上，否则会被再发一次。
            emitted = Math.max(emitted, boundaryStart);
        }

        /** 丢掉已经确认处理完的前缀（最多到 {@link #emitted}）。 */
        private void compact() {
            int base = emitted;
            if (base <= 0) {
                return;
            }
            int keep = buffer.length - base;
            if (keep <= 0) {
                buffer = EMPTY;
            } else {
                byte[] rest = new byte[keep];
                System.arraycopy(buffer, base, rest, 0, keep);
                buffer = rest;
            }
            emitted = 0;
            searchFrom = Math.max(0, searchFrom - base);
            delimEnd = Math.max(0, delimEnd - base);
        }

        private int indexOf(byte[] data, byte[] needle, int start) {
            if (needle.length == 0) {
                return -1;
            }
            outer:
            for (int i = Math.max(0, start); i + needle.length <= data.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (data[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }

        private static int indexOfDoubleCrlf(byte[] data, int from, int end) {
            // 终止符是 4 个字节，最后一组允许「正好收在缓冲区末尾」，
            // 所以上界是 i + 4 <= end，不能写成 i + 3 < end。
            for (int i = Math.max(0, from); i + 4 <= end; i++) {
                if (data[i] == '\r' && data[i + 1] == '\n'
                        && data[i + 2] == '\r' && data[i + 3] == '\n') {
                    return i;
                }
            }
            return -1;
        }
    }
}