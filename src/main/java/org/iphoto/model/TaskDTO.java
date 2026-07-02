package org.iphoto.model;

import java.util.UUID;

public record TaskDTO(
        UUID id,
        String status
) { }
