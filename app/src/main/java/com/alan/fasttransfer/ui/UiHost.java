package com.alan.fasttransfer.ui;

import com.alan.fasttransfer.core.AppSettings;
import com.alan.fasttransfer.core.ReceiveManager;
import com.alan.fasttransfer.core.TransferEngine;
import com.alan.fasttransfer.core.transfer.Peer;
import com.alan.fasttransfer.core.transfer.PeerInfo;
import com.alan.fasttransfer.core.transfer.SendItem;
import com.alan.fasttransfer.core.transfer.TransferHistory;
import com.alan.fasttransfer.core.transfer.TransferSession;

import java.util.List;

/**
 * 页面与 Activity 之间的协作接口。
 */
public interface UiHost {

    TransferEngine engine();

    AppSettings settings();

    TransferHistory history();

    void showIncomingRequest(ReceiveManager.PendingRequest request);

    void showTransferDialog(TransferSession session);

    /** 发送内容（target 为 null 时不发送）。 */
    void sendTo(Peer target, List<SendItem> items);

    /** 依次发送给多台设备。 */
    void sendToMany(List<Peer> targets, List<SendItem> items);

    /** 让用户为某台设备输入 PIN 后再发送。 */
    void requestPinAndSend(Peer target, List<SendItem> items, boolean pinNeeded);

    void onPeerSelected(Peer peer);

    void showManualConnectDialog();

    /** 直接连接一个地址（手动输入或扫码得到）；pin 可为 null。 */
    void connectByAddress(String address, String pin);

    void onTextReceived(PeerInfo from, String text, String savedPath);

    void refreshHeader();
}
