package com.alan.fasttransfer.core.transfer;

/**
 * 仅供离线测试编译使用的 SendItem 占位实现。
 *
 * <p>产品实现依赖 Android 的 Context / Uri / ContentResolver，无法在纯 JVM 下编译。
 * 会话状态机测试只关心 {@link TransferFile} 与 {@link TransferSession} 的字段，
 * 不调用 SendItem 的行为，所以这里只保留字段。</p>
 */
public class SendItem {

    public Object file;
    public Object uri;
    public String name = "";
    public long size = -1;
    public String mime = "application/octet-stream";
    public String text;

    public boolean isText() {
        return text != null;
    }
}
