package com.alan.fasttransfer.core.dto;

import com.google.gson.annotations.SerializedName;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code POST /api/localsend/v2/prepare-upload} 的响应体。
 */
public class PrepareUploadResponseDto {

    @SerializedName("sessionId")
    public String sessionId;

    /** fileId -> 一次性上传令牌。 */
    @SerializedName("files")
    public Map<String, String> files = new HashMap<>();
}
