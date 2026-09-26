package com.alan.fasttransfer.core.transfer;

import com.alan.fasttransfer.core.dto.FileDto;
import com.alan.fasttransfer.core.util.MimeTypes;

/**
 * 传输中的一个条目（发送或接收）。
 */
public class TransferFile {

    /** 文件项状态。 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_FAILED = 3;
    public static final int STATUS_SKIPPED = 4;

    public String id;
    public String name;
    public String mime = "application/octet-stream";
    public long size;
    public long transferred;
    public int status = STATUS_PENDING;
    public String error;

    /** 发送用：本地内容来源。 */
    public transient SendItem item;
    /** 接收用：写入目标（写入完成后置空）。 */
    public transient Object target;
    /** 接收用：落盘后的展示路径。 */
    public String savedPath;
    /** 接收用：可直接交给其它应用打开的 content:// 位置。 */
    public String savedUri;
    /** 接收到的文字内容（仅文字消息）。 */
    public String textContent;

    public FileDto toDto() {
        FileDto dto = new FileDto();
        dto.id = id;
        dto.fileName = name;
        dto.size = size;
        dto.fileType = mime;
        return dto;
    }

    public static TransferFile fromDto(FileDto dto) {
        TransferFile file = new TransferFile();
        file.id = dto.id;
        file.name = dto.fileName == null ? "file" : dto.fileName;
        file.size = dto.size;
        file.mime = dto.fileType == null ? MimeTypes.fromName(file.name) : dto.fileType;
        return file;
    }

    public boolean isText() {
        return textContent != null
                || (mime != null && mime.startsWith("text/"));
    }

    public boolean isDone() {
        return status == STATUS_DONE;
    }

    public static String statusName(int status) {
        switch (status) {
            case STATUS_ACTIVE: return "active";
            case STATUS_DONE: return "done";
            case STATUS_FAILED: return "failed";
            case STATUS_SKIPPED: return "skipped";
            default: return "pending";
        }
    }
}
