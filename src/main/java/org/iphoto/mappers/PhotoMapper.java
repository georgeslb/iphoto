package org.iphoto.mappers;

import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.SpaceType;
import org.iphoto.model.Photo;
import org.mapstruct.Mapper;

import java.time.format.DateTimeFormatter;
import java.util.Set;

@Mapper(componentModel = "spring")
public interface PhotoMapper {



    default Photo photoEntityToPhoto(PhotoEntity entity) {
        return photoEntityToPhoto(entity, null);
    }

    default Photo photoEntityToPhoto(PhotoEntity entity, Set<Photo> duplicates) {
        DateTimeFormatter mainDateFmt = DateTimeFormatter.ofPattern("MMM dd, yyyy");
        DateTimeFormatter fullDateTimeFmt = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        DateTimeFormatter groupDateFmt = DateTimeFormatter.ofPattern("EEEE, MMM dd, yyyy");

        String dateTaken = entity.getDateTaken() != null ? entity.getDateTaken().format(mainDateFmt) : "Unknown Date";
        String fullDateTime = entity.getDateTaken() != null ? entity.getDateTaken().format(fullDateTimeFmt) : "Unknown Date/Time";
        String groupDate = entity.getDateTaken() != null ? entity.getDateTaken().format(groupDateFmt) : "Unknown Date";
        String yearMonth = entity.getDateTaken() != null ? entity.getDateTaken().format(DateTimeFormatter.ofPattern("MMMM yyyy")) : "Other";

        String res = (entity.getWidth() != null && entity.getHeight() != null) ? entity.getWidth() + "x" + entity.getHeight() : "Unknown";

        String thumbnailUrl = "/api/photos/" + entity.getId() + "/thumbnail";
        if (entity.getFileHash() != null) {
            // Cache-bust the browser's URL-keyed image cache: the id stays the same
            // when a photo is edited in place, but the underlying file (and hash) changes.
            thumbnailUrl += "?v=" + entity.getFileHash();
        }

        Boolean inMainSpace = entity.getSpace() != null
                ? entity.getSpace().getType() == SpaceType.MAIN
                : null;

        return new Photo(
                entity.getId().toString(),
                entity.getFileName(),
                thumbnailUrl,
                dateTaken,
                fullDateTime, // New field
                yearMonth,
                groupDate,
                entity.getFileSize() != null ? String.format("%.2f MB", entity.getFileSize() / (1024.0 * 1024.0)) : null,
                res,
                entity.getLocation(),
                entity.getCameraModel(),
                entity.getContentType(),
                entity.getFileHash(),
                entity.getWidth(),
                entity.getHeight(),
                entity.getFileSystemCreationDate(),
                inMainSpace,
                entity.getDuration(),
                duplicates) ;
    }
}
