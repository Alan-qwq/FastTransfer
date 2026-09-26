package com.alan.fasttransfer.core.dto;

import com.google.gson.annotations.SerializedName;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code POST /api/localsend/v2/prepare-upload} 的请求体。
 */
public class PrepareUploadRequestDto {

    @SerializedName("info")
    public DeviceInfoDto info;

    /** fileId -> 文件元数据。 */
    @SerializedName("files")
    public Map<String, FileDto> files = new HashMap<>();
}
