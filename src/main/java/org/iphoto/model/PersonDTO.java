package org.iphoto.model;

import java.util.UUID;

public record PersonDTO(
        UUID id,
        String name,
        FaceDTO coverPhoto,
        Integer photoCount
) { }
