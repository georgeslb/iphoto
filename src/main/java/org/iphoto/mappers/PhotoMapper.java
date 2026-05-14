package org.iphoto.mappers;

import org.iphoto.entities.PhotoEntity;
import org.iphoto.model.Photo;
import org.mapstruct.Mapper;

import java.time.format.DateTimeFormatter;

@Mapper(componentModel = "spring")
public interface PhotoMapper {

    default Photo photoEntityToPhoto(PhotoEntity entity) {
        DateTimeFormatter mainDateFmt = DateTimeFormatter.ofPattern("MMM dd, yyyy");
        DateTimeFormatter fullDateTimeFmt = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm:ss");
        DateTimeFormatter groupDateFmt = DateTimeFormatter.ofPattern("EEEE, MMM dd, yyyy");

        String dateTaken = entity.getDateTaken() != null ? entity.getDateTaken().format(mainDateFmt) : "Unknown Date";
        String fullDateTime = entity.getDateTaken() != null ? entity.getDateTaken().format(fullDateTimeFmt) : "Unknown Date/Time";
        String groupDate = entity.getDateTaken() != null ? entity.getDateTaken().format(groupDateFmt) : "Unknown Date";
        String yearMonth = entity.getDateTaken() != null ? entity.getDateTaken().format(DateTimeFormatter.ofPattern("MMMM yyyy")) : "Other";

        String res = (entity.getWidth() != null && entity.getHeight() != null) ? entity.getWidth() + "x" + entity.getHeight() : "Unknown";

        return new Photo(
                entity.getId().toString(),
                entity.getFileName(),
                "/api/photos/" + entity.getId() + "/thumbnail",
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
                entity.getFileSystemCreationDate()
        );
    }
}
