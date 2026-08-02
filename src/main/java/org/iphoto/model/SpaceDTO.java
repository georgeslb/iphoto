package org.iphoto.model;

import java.time.LocalDateTime;
import java.util.UUID;

public record SpaceDTO(
    UUID id,
    String name,
    Integer photoCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    boolean albumAllowed,
    boolean allowCopyToMain,
    boolean deletionAllowed,
    boolean deleted
) {}
