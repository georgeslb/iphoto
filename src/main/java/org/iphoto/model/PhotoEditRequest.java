package org.iphoto.model;

public record PhotoEditRequest(
    int cropX,
    int cropY,
    int width,
    int height,
    int rotation,
    boolean saveAsNew
) {}
