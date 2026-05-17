package org.iphoto.model;

public record PhotoMetadata (

    String id,
    String aperture,
    String shutterSpeed,
    String iso,
    String focalLength,
    String lensModel,
    Double latitude,
    Double longitude,
    String codec,
    String bitRate,
    String frameRate
) {}
