package org.iphoto.model.request;

import java.util.List;
import java.util.UUID;

public record PhotoSelectionRequest(MenuSelectionRequest menuSelectionRequest, List<UUID> photosId) {
}
