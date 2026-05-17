package org.iphoto.mappers;

import org.iphoto.entities.PhotoMetadataEntity;
import org.iphoto.model.PhotoMetadata;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface PhotoMetadataMapper {

    PhotoMetadata photoMetadataEntityToPhotoMetadata(PhotoMetadataEntity entity);
}
