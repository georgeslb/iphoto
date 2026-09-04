package org.iphoto.model.request;

import java.util.Set;
import java.util.UUID;

public record UnlinkPhotoDuplicate(
        UUID unlinkedPhotoId,
        Set<UUID> duplicatesId
) {
}
