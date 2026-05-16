package org.iphoto.model;

import java.util.List;

public record DuplicateGroupDTO(
    String hash,
    List<Photo> photos
) {}
