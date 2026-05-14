package org.iphoto.model;

import java.util.List;
import java.util.UUID;

public record AlbumPhotoRequest(
    List<UUID> photoIds
) {}
