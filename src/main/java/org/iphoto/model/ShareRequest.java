package org.iphoto.model;

import java.util.UUID;

public record ShareRequest(
    String name,
    int daysAvailable,
    String password,
    UUID albumId
) {}
