package org.iphoto.model.request;

import org.springframework.web.multipart.MultipartFile;

public record ChunkUploadRequest(
    String uploadId,
    String fileName,
    long fileSize,
    int chunkIndex,
    int totalChunks,
    String contentType,
    MultipartFile chunk // The actual file chunk
) {}
