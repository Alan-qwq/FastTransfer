package com.alan.fasttransfer.core.transfer;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次传输会话（发送或接收）。
 */
public class TransferSession {

    public static final int DIRECTION_SEND = 0;
    public static final int DIRECTION_RECEIVE = 1;

    public static final int STATE_PREPARING = 0;
    public static final int STATE_WAITING_PEER = 1;
    public static final int STATE_RUNNING = 2;
    public static final int STATE_DONE = 3;
    public static final int STATE_FAILED = 4;
    public static final int STATE_CANCELLED = 5;
    public static final int STATE_DECLINED = 6;

    public String id;
    public int direction = DIRECTION_SEND;
    public PeerInfo peer = new PeerInfo();
    public final List<TransferFile> files = new ArrayList<>();
    /** 会被工作线程改写、UI 线程读取，用 volatile 保证可见性。 */
    public volatile int state = STATE_PREPARING;
    public String error;
    public long startedAt = System.currentTimeMillis();
    public long finishedAt;
    /** 服务端返回的会话 ID。 */
    public String remoteSessionId;
    public boolean pinRequired;

    public volatile boolean cancelled;

    /**
     * 界面该怎么画。
     *
     * <p>抽成独立判断是为了让它可离线测试：之前对话框在每次更新时
     * **无条件**把标题刷回「正在发送」，覆盖掉已经渲染好的完成状态，
     * 于是传完了界面上还显示正在发送。</p>
     */
    public enum RenderState {
        /** 正在建立连接。 */
        PREPARING,
        /** 等待对端确认。 */
        WAITING_PEER,
        /** 传输中。 */
        RUNNING,
        /** 完成。 */
        DONE,
        /** 已取消。 */
        CANCELLED,
        /** 被拒绝。 */
        DECLINED,
        /** 失败。 */
        FAILED
    }

    /** 当前应该呈现的状态。 */
    public RenderState renderState() {
        switch (state) {
            case STATE_DONE:
                return RenderState.DONE;
            case STATE_CANCELLED:
                return RenderState.CANCELLED;
            case STATE_DECLINED:
                return RenderState.DECLINED;
            case STATE_FAILED:
                return RenderState.FAILED;
            case STATE_WAITING_PEER:
                return RenderState.WAITING_PEER;
            case STATE_PREPARING:
                return RenderState.PREPARING;
            default:
                return RenderState.RUNNING;
        }
    }

    /** 是否是终态：到终态后界面不再回到「进行中」。 */
    public boolean renderIsTerminal() {
        RenderState render = renderState();
        return render == RenderState.DONE || render == RenderState.CANCELLED
                || render == RenderState.DECLINED || render == RenderState.FAILED;
    }

    public long totalBytes() {
        long total = 0;
        for (TransferFile file : files) {
            total += Math.max(0, file.size);
        }
        return total;
    }

    public long transferredBytes() {
        long total = 0;
        for (TransferFile file : files) {
            total += Math.max(0, file.transferred);
        }
        return total;
    }

    public int doneCount() {
        int count = 0;
        for (TransferFile file : files) {
            if (file.status == TransferFile.STATUS_DONE) {
                count++;
            }
        }
        return count;
    }

    public int fileCount() {
        return files.size();
    }

    public boolean isFinished() {
        return state == STATE_DONE || state == STATE_FAILED
                || state == STATE_CANCELLED || state == STATE_DECLINED;
    }

    public boolean isSuccess() {
        return state == STATE_DONE;
    }

    public String statusKey() {
        switch (state) {
            case STATE_DONE: return "success";
            case STATE_FAILED: return "failed";
            case STATE_CANCELLED: return "cancelled";
            case STATE_DECLINED: return "declined";
            default: return "running";
        }
    }

    /** 首次出现的文字内容（用于展示）。 */
    public TransferFile firstTextFile() {
        for (TransferFile file : files) {
            if (file.textContent != null) {
                return file;
            }
        }
        return null;
    }
}
