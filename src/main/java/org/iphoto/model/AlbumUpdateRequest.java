package org.iphoto.model;

import java.util.UUID;

public record AlbumUpdateRequest(
    String name,
    String description,
    UUID coverPhotoId // Optional: ID of a photo to set as cover
) {}
