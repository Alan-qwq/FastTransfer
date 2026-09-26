package com.alan.fasttransfer.core.transfer;

/**
 * 传输层的状态回调（回调发生在工作线程，UI 层需自行切回主线程）。
 */
public interface TransferListener {

    /** 会话状态或进度发生变化。 */
    void onSessionUpdated(TransferSession session);

    /** 会话结束。 */
    void onSessionFinished(TransferSession session);

    /** 收到一段文字（可能是文件，也可能是文字消息）。 */
    void onTextReceived(PeerInfo from, String text, String savedPath);
}
