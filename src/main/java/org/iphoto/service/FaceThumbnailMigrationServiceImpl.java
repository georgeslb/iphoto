package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.FaceEntity;
import org.iphoto.model.FaceThumbnailMigrationSummaryDTO;
import org.iphoto.repository.FaceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaceThumbnailMigrationServiceImpl implements FaceThumbnailMigrationService {

    private final FaceRepository faceRepository;
    private final FaceThumbnailStorageService faceThumbnailStorageService;

    private static final int PAGE_SIZE = 200;

    @Override
    public FaceThumbnailMigrationSummaryDTO migrateAll() {
        log.info("start to migrate faces");
        int scanned = 0, migrated = 0, failed = 0;
        // Deliberately no surrounding @Transactional here, same as StorageMigrationServiceImpl -
        // each faceRepository.save() below gets its own short transaction rather than one huge one
        // spanning the whole backlog (see TODO.md's 2026-09-12 production incident: a single long-
        // running transaction over a large face backlog is exactly what took prod down once
        // already). Always re-fetches page 0, not slice.nextPageable() - the query's own result
        // set shrinks by exactly the rows just migrated on every iteration (a migrated row no
        // longer matches "thumbnailPath IS NULL"), so an advancing OFFSET would skip whatever used
        // to be the next page.
        Pageable pageable = PageRequest.of(0, PAGE_SIZE);
        while (true) {
            Slice<FaceEntity> slice = faceRepository.findByFaceThumbnailIsNotNullAndThumbnailPathIsNullFetchPhoto(pageable);
            if (slice.getContent().isEmpty()) {
                break;
            }
            int migratedThisPage = 0;
            for (FaceEntity face : slice.getContent()) {
                scanned++;
                try {
                    byte[] regenerated = FaceDetectionServiceImpl.regenerateFaceThumbnail(face.getFaceThumbnail());
                    String thumbnailPath = faceThumbnailStorageService.save(regenerated, face.getPhoto().getDateTaken());
                    face.setThumbnailPath(thumbnailPath);
                    face.setFaceThumbnail(null);
                    faceRepository.save(face);
                    migrated++;
                    migratedThisPage++;
                } catch (Exception e) {
                    failed++;
                    log.warn("Failed to migrate face thumbnail for face {}", face.getId(), e);
                }
            }
            // A failed row keeps its faceThumbnail blob and null thumbnailPath, so it still
            // matches this same query on the next iteration (always re-fetched at page 0, see
            // above) - if an entire page fails outright, every further iteration would just
            // refetch the exact same unfixable rows forever. Stop once a page makes zero progress;
            // whatever's left needs manual investigation (see the failed count in the summary),
            // not an infinite retry loop here.
            if (migratedThisPage == 0) {
                break;
            }
        }
        log.info("finish faces migration scanned {}, migrated {}, failed {}", scanned, migrated, failed);
        return new FaceThumbnailMigrationSummaryDTO(scanned, migrated, failed);
    }
}
