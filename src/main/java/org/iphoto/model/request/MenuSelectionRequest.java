package org.iphoto.model.request;

import java.util.List;
import java.util.UUID;

public record MenuSelectionRequest(String menu, List<UUID> spaceIds, List<UUID> albumIds, Boolean lightbox) {
}
