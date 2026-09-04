package org.iphoto.model;

import java.util.List;

public record CheckHashesResponse(List<String> existingHashes) {
}
