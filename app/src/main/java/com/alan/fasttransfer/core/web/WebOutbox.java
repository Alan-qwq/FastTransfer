package com.alan.fasttransfer.core.web;

import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 「手机要发给电脑」的待发队列。
 *
 * <p>网页传输的方向应该是手机主动发：用户在手机上选好文件、点发送，
 * 电脑浏览器打开页面就能看到并保存。这一层就是暂存这些待发文件。</p>
 *
 * <p>刻意<b>不复制文件</b>：队列里存的是原始 {@code content://} Uri，
 * 电脑点下载时由 App 用自己的读权限流式读出来直接写进 HTTP 响应。
 * 这样传一个 2GB 的视频也不会先占掉 2GB 空间、也不用等一次拷贝。</p>
 *
 * <p>代价是它依赖 App 进程活着。所以 {@link #persist} 会把 Uri 记到
 * SharedPreferences 里，进程重启后还能恢复大部分条目。</p>
 */
public final class WebOutbox {

    /** 一条待发内容：文件（{@link #uri}）或纯文字（{@link #text}）。 */
    public static final class Item {
        /** 下载用的随机令牌：不可猜，避免别人遍历下载。 */
        public String token = "";
        public String name = "";
        public long size;
        public String mime = "application/octet-stream";
        /** 原始 content:// Uri。 */
        public String uri = "";
        /** 纯文字消息正文；非空时这一条是文字而不是文件。 */
        public String text;
        public long time;

        /** 是文字消息。 */
        public boolean isText() {
            return text != null;
        }

        public boolean usable() {
            if (token == null || token.isEmpty() || name == null || name.isEmpty()) {
                return false;
            }
            return isText() || (uri != null && !uri.isEmpty());
        }
    }

    /** 队列上限，防止用户误点多选把内存撑爆。 */
    private static final int MAX_ITEMS = 500;

    private static final Object LOCK = new Object();
    private static final List<Item> ITEMS = new CopyOnWriteArrayList<>();

    /** 本进程内新增条目时通知网页（省得网页只能靠轮询等）。 */
    private static volatile Runnable listener;

    private WebOutbox() {
    }

    /** 网页端在轮询时注册回调，用于「有新文件了」的即时感知。 */
    public static void setListener(Runnable value) {
        listener = value;
    }

    /** 把手机上的文件加入待发队列，返回生成的条目。 */
    public static Item add(Context context, Uri uri, String name, long size, String mime) {
        if (uri == null || name == null || name.isEmpty()) {
            return null;
        }
        Item item = new Item();
        item.name = name;
        item.size = size;
        item.mime = mime == null || mime.isEmpty()
                ? com.alan.fasttransfer.core.util.MimeTypes.fromName(name) : mime;
        item.uri = uri.toString();
        return enqueue(context, item);
    }

    /** 把一段文字加入待发队列；电脑那边会显示成可复制的文本块。 */
    public static Item addText(Context context, String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Item item = new Item();
        item.name = com.alan.fasttransfer.core.LocalsendProtocol.TEXT_FILE_NAME;
        item.text = text;
        byte[] bytes;
        try {
            bytes = text.getBytes("UTF-8");
        } catch (Exception e) {
            bytes = text.getBytes();
        }
        item.size = bytes.length;
        item.mime = "text/plain";
        return enqueue(context, item);
    }

    private static Item enqueue(Context context, Item item) {
        item.token = WebApproval.newToken();
        item.time = System.currentTimeMillis();

        synchronized (LOCK) {
            ITEMS.add(0, item);
            while (ITEMS.size() > MAX_ITEMS) {
                ITEMS.remove(ITEMS.size() - 1);
            }
        }
        persist(context);
        Runnable notify = listener;
        if (notify != null) {
            try {
                notify.run();
            } catch (Throwable ignored) {
            }
        }
        return item;
    }

    /** 当前待发文件，按加入时间倒序。 */
    public static List<Item> list() {
        List<Item> copy = new ArrayList<>(ITEMS);
        Collections.sort(copy, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Long.compare(b.time, a.time);
            }
        });
        return copy;
    }

    /** 按令牌找条目；找不到返回 null。 */
    public static Item find(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        for (Item item : ITEMS) {
            if (item.token.equals(token)) {
                return item;
            }
        }
        return null;
    }

    public static void remove(Context context, String token) {
        if (token == null) {
            return;
        }
        synchronized (LOCK) {
            for (int i = ITEMS.size() - 1; i >= 0; i--) {
                if (token.equals(ITEMS.get(i).token)) {
                    ITEMS.remove(i);
                }
            }
        }
        persist(context);
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            ITEMS.clear();
        }
        persist(context);
    }

    public static int size() {
        return ITEMS.size();
    }

    // ==================== 持久化 ====================

    private static final String PREFS = "fast_transfer_outbox";
    private static final String KEY = "items";

    /**
     * 把队列记到磁盘。
     *
     * <p>只保证「重启后还能显示出来」：Uri 的读权限是 App 自己持有的，
     * 只要文件还在就能读；读不到时下载会明确报错，而不是静默失败。</p>
     */
    static void persist(Context context) {
        if (context == null) {
            return;
        }
        try {
            String json = com.alan.fasttransfer.core.util.Json.toJson(list());
            prefs(context).edit().putString(KEY, json).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 进程启动时调用，恢复上次没发完的队列。 */
    public static void restore(Context context) {
        if (context == null || !ITEMS.isEmpty()) {
            return;
        }
        try {
            String raw = prefs(context).getString(KEY, null);
            if (raw == null || raw.isEmpty()) {
                return;
            }
            List<Item> parsed = com.alan.fasttransfer.core.util.Json.fromJson(raw,
                    new com.google.gson.reflect.TypeToken<List<Item>>() {
                    }.getType());
            if (parsed == null) {
                return;
            }
            synchronized (LOCK) {
                for (Item item : parsed) {
                    if (item != null && item.usable()) {
                        ITEMS.add(item);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
