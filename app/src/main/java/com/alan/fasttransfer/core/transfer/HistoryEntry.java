package com.alan.fasttransfer.core.transfer;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次传输的历史记录（轻量、可 JSON 序列化）。
 */
public class HistoryEntry {

    /** 一条记录里最多存这么多可打开位置，避免历史 JSON 无限膨胀。 */
    private static final int MAX_SAVED_URIS = 20;

    public String id;
    public int direction;
    public String peerAlias = "";
    public String peerIp = "";
    public int fileCount;
    public long totalBytes;
    public String statusKey = "success";
    public long startedAt;
    public long finishedAt;
    /** 第一个文件名，便于在列表里显示。 */
    public String firstFileName = "";
    /** 文字消息内容（仅文字）。 */
    public String textPreview;
    /**
     * 收到文件的保存位置（content://），点记录时用它跳转打开。
     *
     * <p>发出去的方向没有这个 —— 文件本来就在对方那里。</p>
     */
    public List<String> savedUris;
    /** 第一个文件的 MIME，打开时要用它挑应用。 */
    public String firstMime = "";
    /** 保存位置的说明文字，打不开时至少能告诉用户去哪找。 */
    public String savedPathHint = "";

    public static HistoryEntry from(TransferSession session) {
        HistoryEntry entry = new HistoryEntry();
        entry.id = session.id;
        entry.direction = session.direction;
        entry.peerAlias = session.peer == null ? "" : session.peer.displayName();
        entry.peerIp = session.peer == null ? "" : session.peer.ip;
        entry.fileCount = session.fileCount();
        entry.totalBytes = session.totalBytes();
        entry.statusKey = session.statusKey();
        entry.startedAt = session.startedAt;
        entry.finishedAt = session.finishedAt == 0 ? System.currentTimeMillis() : session.finishedAt;
        if (!session.files.isEmpty()) {
            TransferFile first = session.files.get(0);
            entry.firstFileName = first.name;
            entry.firstMime = first.mime;
            if (first.textContent != null) {
                String text = first.textContent;
                entry.textPreview = text.length() > 120 ? text.substring(0, 120) + "…" : text;
            }
            entry.savedUris = collectSavedUris(session);
            entry.savedPathHint = first.savedPath == null ? "" : first.savedPath;
        }
        return entry;
    }

    /** 把本次会话里所有「能打开的位置」收集起来。 */
    private static List<String> collectSavedUris(TransferSession session) {
        List<String> uris = new ArrayList<>();
        for (TransferFile file : session.files) {
            if (file.savedUri == null || file.savedUri.isEmpty()) {
                continue;
            }
            uris.add(file.savedUri);
            if (uris.size() >= MAX_SAVED_URIS) {
                break;
            }
        }
        return uris.isEmpty() ? null : uris;
    }

    public boolean isText() {
        return textPreview != null;
    }

    /** 这条记录能不能点开（有可打开的位置，且不是文字消息）。 */
    public boolean canOpen() {
        return !isText() && savedUris != null && !savedUris.isEmpty();
    }
}

