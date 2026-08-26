package org.iphoto.model;

import java.util.UUID;

public record FaceDTO(
        UUID id,
        int x,
        int y,
        int width,
        int height,
        String image,
        String personName
) {}
