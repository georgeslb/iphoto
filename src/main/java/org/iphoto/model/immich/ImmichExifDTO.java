package org.iphoto.model.immich;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// AssetResponseDto.exifInfo. BEST-EFFORT SHAPE - field names follow Immich's documented
// ExifResponseDto but haven't been verified against a live server; ignoreUnknown protects
// against harmless drift, but a field silently coming back null because its real name differs
// won't - if latitude/longitude/dateTimeOriginal end up always null on a live run, check the
// actual JSON body first.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImmichExifDTO(
        String make,
        String model,
        String lensModel,
        Double fNumber,
        Double focalLength,
        Integer iso,
        String exposureTime,
        String dateTimeOriginal,
        Double latitude,
        Double longitude,
        String city,
        String state,
        String country,
        String description
) {
}
