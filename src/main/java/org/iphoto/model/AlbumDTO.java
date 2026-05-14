package org.iphoto.model;

import java.time.LocalDateTime;
import java.util.UUID;

public record AlbumDTO(
    UUID id,
    String name,
    String description,
    Photo coverPhoto, // Simplified Photo DTO for cover
    Integer photoCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
