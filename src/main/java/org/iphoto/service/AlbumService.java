package org.iphoto.service;

import org.iphoto.entities.AlbumEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.model.AlbumDTO;
import org.iphoto.model.DayBucketDTO;
import org.iphoto.model.Photo;
import org.iphoto.model.request.AlbumCreateRequest;
import org.iphoto.model.request.AlbumUpdateRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AlbumService {

    AlbumDTO createAlbum(SpaceEntity space, AlbumCreateRequest request);

    List<AlbumDTO> getAllAlbums();

    AlbumDTO getAlbumById(UUID id);

    AlbumDTO updateAlbum(UUID id, AlbumUpdateRequest request);

    void deleteAlbum(UUID id);

    AlbumDTO addPhotosToAlbum(UUID albumId, List<UUID> photoIds);

    AlbumDTO removePhotosFromAlbum(UUID albumId, List<UUID> photoIds);

    Page<Photo> getAlbumPhotos(UUID albumId, Pageable pageable);

    // Day-index browsing for Album detail (see PhotoService.getDailyPhotoCounts, the Library/
    // Space equivalent) - an album references photos rather than owning them, so this groups
    // through the album_photos join instead of by space_id. Cached (see DayIndexCache), evicted
    // on addPhotosToAlbum/removePhotosFromAlbum above. isVideo is the photo/video type filter
    // (null = every type).
    List<DayBucketDTO> getAlbumDailyPhotoCounts(UUID albumId, Boolean isVideo);

    Page<Photo> getAlbumPhotosForDay(UUID albumId, LocalDate day, Pageable pageable, Boolean isVideo);

    // Guest/share-flow variants: deliberately use the unfiltered album lookup since a guest
    // share session has no matching UserEntity (the space-filter aspect would throw trying to
    // resolve one). Album/photo-level authorization for guests is enforced separately, by
    // ShareAccessFilter checking the requested album against the share the guest authenticated
    // for — do not reuse these from any authenticated-user-facing endpoint.
    Page<Photo> getAlbumPhotosForShare(UUID albumId, Pageable pageable);

    Photo getAlbumPhotoForShare(UUID albumId, UUID photoId);

    // Day-index browsing for the guest/share view - same grouping as getAlbumDailyPhotoCounts /
    // getAlbumPhotosForDay above, but through the unfiltered album lookup (see the ...ForShare
    // note above). Lets SharedAlbumView reuse the same lazy per-day timeline as every
    // authenticated photo-listing view instead of a flat page-by-page scroll.
    List<DayBucketDTO> getAlbumDailyPhotoCountsForShare(UUID albumId);

    Page<Photo> getAlbumPhotosForDayForShare(UUID albumId, LocalDate day, Pageable pageable);

    AlbumDTO convertToDto(AlbumEntity album);

    AlbumEntity updateAlbum(AlbumEntity albumEntity);
}
