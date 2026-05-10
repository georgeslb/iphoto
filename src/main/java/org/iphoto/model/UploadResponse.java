package org.iphoto.model;

import java.util.UUID;

public record UploadResponse(
    String message,
    UUID taskId
) {}
