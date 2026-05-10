package org.iphoto.model;

import java.util.UUID;

public record LocationDTO(
    UUID id,
    Double latitude,
    Double longitude,
    String title,
    String thumbnailUrL
) {}
