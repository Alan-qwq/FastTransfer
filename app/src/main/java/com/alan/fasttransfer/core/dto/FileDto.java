package com.alan.fasttransfer.core.dto;

import com.google.gson.annotations.SerializedName;

import java.util.HashMap;
import java.util.Map;

/**
 * 传输文件元数据，对应 LocalSend 协议中的 {@code FileDto}。
 */
public class FileDto {

    @SerializedName("id")
    public String id;

    @SerializedName("fileName")
    public String fileName;

    @SerializedName("size")
    public long size;

    @SerializedName("fileType")
    public String fileType;

    @SerializedName("sha256")
    public String sha256;

    @SerializedName("preview")
    public String preview;

    @SerializedName("metadata")
    public Map<String, Object> metadata;

    public FileDto() {
    }

    public FileDto(String id, String fileName, long size, String fileType) {
        this.id = id;
        this.fileName = fileName;
        this.size = size;
        this.fileType = fileType == null ? "application/octet-stream" : fileType;
    }

    public FileDto copy() {
        FileDto dto = new FileDto();
        dto.id = id;
        dto.fileName = fileName;
        dto.size = size;
        dto.fileType = fileType;
        dto.sha256 = sha256;
        dto.preview = preview;
        dto.metadata = metadata;
        return dto;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("fileName", fileName);
        map.put("size", size);
        map.put("fileType", fileType);
        if (sha256 != null) {
            map.put("sha256", sha256);
        }
        if (preview != null) {
            map.put("preview", preview);
        }
        if (metadata != null) {
            map.put("metadata", metadata);
        }
        return map;
    }
}
