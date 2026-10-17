package org.iphoto.model;

import java.util.List;
import java.util.Map;

public record CheckHashesResponse(
        List<String> existingHashes,
        Map<String, Boolean> statuses
) {
    public CheckHashesResponse(List<String> existingHashes) {
        this(existingHashes, Map.of());
    }
}
