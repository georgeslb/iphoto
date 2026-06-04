package org.iphoto.model.request;

import java.util.UUID;

public record ShareRequest(
    String name,
    int daysAvailable,
    String password,
    UUID albumId
) {}
