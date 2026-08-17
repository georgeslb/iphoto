package org.iphoto.model;

public record UploadChunkResponse(String status, String chunkIndex, TaskDTO task) {
}
