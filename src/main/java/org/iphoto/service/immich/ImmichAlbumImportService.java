package org.iphoto.service.immich;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.AlbumEntity;
import org.iphoto.entities.ShareEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.exception.ImmichApiException;
import org.iphoto.model.ImmichAlbumImportSummaryDTO;
import org.iphoto.model.immich.ImmichAlbumDetailDTO;
import org.iphoto.model.immich.ImmichAlbumSummaryDTO;
import org.iphoto.model.immich.ImmichAssetRefDTO;
import org.iphoto.model.immich.ImmichSharedLinkDTO;
import org.iphoto.repository.AlbumRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.ShareRepository;
import org.iphoto.service.AlbumService;
import org.iphoto.service.ShareService;
import org.iphoto.service.SpaceService;
import org.iphoto.service.UserService;
import org.iphoto.service.UserService.ImmichCredentials;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.*;

// Recreates Immich albums (and their album-level shared links) in iphoto - run once photo import
// is well underway, since a photo must already exist locally (see PhotoRepository.existsById)
// before AlbumService.addPhotosToAlbum will accept it. Runs synchronously on the triggering user's
// own request thread (small scale, unlike per-day asset import) so @MainSpaceLimited repository
// methods resolve the current space normally via SecurityContextHolder.
//
// Each album and each shared link is processed in its own transaction (via TransactionTemplate,
// not a single @Transactional spanning the whole run) so one broken item can't mark the whole
// run's transaction rollback-only and silently undo everything already imported before it - see
// the per-item try/catch below, which only works correctly because each unit truly commits or
// rolls back on its own.
@Service
@RequiredArgsConstructor
@Slf4j
public class ImmichAlbumImportService {

    private static final String SOURCE = "IMMICH";

    private final ImmichClient immichClient;
    private final AlbumRepository albumRepository;
    private final AlbumService albumService;
    private final ShareRepository shareRepository;
    private final SpaceService spaceService;
    private final PhotoRepository photoRepository;
    private final TransactionTemplate transactionTemplate;
    private final UserService userService;

    public ImmichAlbumImportSummaryDTO triggerAlbumImport(UserEntity user) {
        ImmichCredentials creds = userService.requireImmichCredentials(user);
        List<String> warnings = new ArrayList<>();
        int albumsCreated = 0, albumsUpdated = 0, photosLinked = 0, photosSkippedNotYetImported = 0;
        int sharedLinksCreated = 0, sharedLinksSkipped = 0;

        List<ImmichAlbumSummaryDTO> albums;
        try {
            albums = immichClient.listAlbums(creds, true);
        } catch (Exception e) {
            throw new ImmichApiException("Failed to list Immich albums", e);
        }

        // Tracks which Immich album id maps to which iphoto album, for the shared-link pass below.
        Map<String, UUID> importedAlbumIds = new HashMap<>();

        for (ImmichAlbumSummaryDTO albumSummary : albums) {
            try {
                AlbumResult result = transactionTemplate.execute(status -> processOneAlbum(albumSummary, user, creds));
                if (result != null) {
                    importedAlbumIds.put(albumSummary.id(), result.albumId());
                    if (result.wasNew()) albumsCreated++; else albumsUpdated++;
                    photosLinked += result.photosLinked();
                    photosSkippedNotYetImported += result.photosSkipped();
                }
            } catch (Exception e) {
                warnings.add("Album '" + albumSummary.albumName() + "' (immich id " + albumSummary.id() + "): " + e.getMessage());
                log.warn("Failed to import Immich album {}", albumSummary.id(), e);
            }
        }

        List<ImmichSharedLinkDTO> links;
        try {
            links = immichClient.listSharedLinks(creds);
        } catch (Exception e) {
            warnings.add("Failed to list Immich shared links: " + e.getMessage());
            links = List.of();
        }

        for (ImmichSharedLinkDTO link : links) {
            if (!link.isAlbumLevel()) {
                warnings.add("Shared link " + link.id() + " is asset-level - iphoto shares are album-only, skipped.");
                sharedLinksSkipped++;
                continue;
            }
            try {
                boolean created = Boolean.TRUE.equals(transactionTemplate.execute(status ->
                        processOneSharedLink(link, user, importedAlbumIds)));
                if (created) sharedLinksCreated++;
            } catch (Exception e) {
                warnings.add("Shared link " + link.id() + ": " + e.getMessage());
                sharedLinksSkipped++;
                log.warn("Failed to import Immich shared link {}", link.id(), e);
            }
        }

        return new ImmichAlbumImportSummaryDTO(albumsCreated, albumsUpdated, photosLinked, photosSkippedNotYetImported, sharedLinksCreated, sharedLinksSkipped, warnings);
    }

    private AlbumResult processOneAlbum(ImmichAlbumSummaryDTO albumSummary, UserEntity user, ImmichCredentials creds) {
        boolean existedBefore = albumRepository.findByExternalSourceAndExternalId(SOURCE, albumSummary.id()).isPresent();
        AlbumEntity album = albumRepository.findByExternalSourceAndExternalId(SOURCE, albumSummary.id())
                .orElseGet(() -> AlbumEntity.builder()
                        .space(spaceService.getDefaultSpaceForUser(user))
                        .name(albumSummary.albumName())
                        .photoCount(0)
                        .externalSource(SOURCE)
                        .externalId(albumSummary.id())
                        .build());
        album = albumRepository.save(album);

        ImmichAlbumDetailDTO detail = immichClient.getAlbumDetail(creds, albumSummary.id());
        List<UUID> linkable = new ArrayList<>();
        int skipped = 0;
        if (detail.assets() != null) {
            for (ImmichAssetRefDTO ref : detail.assets()) {
                try {
                    UUID assetId = UUID.fromString(ref.id());
                    if (photoRepository.existsById(assetId)) {
                        linkable.add(assetId);
                    } else {
                        skipped++;
                    }
                } catch (IllegalArgumentException ignored) {
                    skipped++;
                }
            }
        }
        if (!linkable.isEmpty()) {
            albumService.addPhotosToAlbum(album.getId(), linkable);
        }
        return new AlbumResult(album.getId(), !existedBefore, linkable.size(), skipped);
    }

    // Returns true if a new ShareEntity was created (false if it already existed, or if the
    // target album genuinely can't be found).
    private Boolean processOneSharedLink(ImmichSharedLinkDTO link, UserEntity user, Map<String, UUID> importedAlbumIds) {
        if (shareRepository.findByExternalSourceAndExternalId(SOURCE, link.id()).isPresent()) {
            return false; // already imported on a previous run
        }

        UUID iphotoAlbumId = importedAlbumIds.get(link.album().id());
        if (iphotoAlbumId == null) {
            // Not in this run's "owned albums" pass (e.g. an album shared with this user) - look
            // it up directly rather than assuming it was never imported.
            iphotoAlbumId = albumRepository.findByExternalSourceAndExternalId(SOURCE, link.album().id())
                    .map(AlbumEntity::getId)
                    .orElse(null);
        }
        if (iphotoAlbumId == null) {
            throw new NoSuchElementException("points at an Immich album that hasn't been imported");
        }
        UUID resolvedAlbumId = iphotoAlbumId;

        AlbumEntity album = albumRepository.findById(resolvedAlbumId)
                .orElseThrow(() -> new NoSuchElementException("imported album " + resolvedAlbumId + " no longer exists"));
        String userMd5 = ShareService.computeUserMd5(user.getId());
        String shareName = resolveShareName(link, album, userMd5);

        ShareEntity share = ShareEntity.builder()
                .space(spaceService.getDefaultSpaceForUser(user))
                .name(shareName)
                .userMd5(userMd5)
                .album(album)
                .externalSource(SOURCE)
                .externalId(link.id())
                .build();
        shareRepository.save(share);
        return true;
    }

    // Share names double as the URL path segment (/share/{userMd5}/{name}), and must be unique
    // per space (name_space_unique_shares). Reuse Immich's own shared-link `key` verbatim as the
    // name whenever possible - it's exactly the token Immich itself used to identify this share
    // (immich-host/share/{key}), already URL-path-safe, and reusing it is the closest iphoto can
    // get to "the same share link" carrying over (the /share/{userMd5}/... prefix is unavoidable -
    // every iphoto share is addressed that way, not just Immich-imported ones). Case is preserved
    // deliberately - lowercasing/slugifying it the way sanitizeShareName does would change the
    // token and defeat the point. Only falls back to a generated slug if Immich sent no key, or if
    // (astronomically unlikely for a random token, but checked rather than trusted blindly - a
    // save-time unique-constraint violation would abort this share's whole per-item transaction)
    // the key happens to collide with a share name this user already has.
    private String resolveShareName(ImmichSharedLinkDTO link, AlbumEntity album, String userMd5) {
        if (StringUtils.hasText(link.key())) {
            if (shareRepository.findByUserMd5AndName(userMd5, link.key()).isEmpty()) {
                return link.key();
            }
            log.warn("Immich shared link key '{}' collides with an existing share name, falling back to a generated name", link.key());
        }
        return sanitizeShareName(album.getName() + "-" + link.id());
    }

    // Fallback only (see resolveShareName) - must still be unique per space
    // (name_space_unique_shares), so derive one from the album name plus a slice of the Immich
    // link id to keep collisions across differently-shared albums with the same name rare.
    private static String sanitizeShareName(String raw) {
        String sanitized = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("-{2,}", "-");
        return sanitized.length() > 100 ? sanitized.substring(0, 100) : sanitized;
    }

    private record AlbumResult(UUID albumId, boolean wasNew, int photosLinked, int photosSkipped) {
    }
}
