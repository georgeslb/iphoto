package org.iphoto.model;

public record Photo(
    String id,
    String title,
    String url,
    String date,
    String yearMonth,
    String groupDate,
    String size,
    String resolution,
    String location,
    String camera,
    String contentType,
    String fileHash,
    Integer width,
    Integer height
) {}
