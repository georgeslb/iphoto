package org.iphoto.controller;

import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.ImmichAlbumImportSummaryDTO;
import org.iphoto.model.ImmichImportDayDTO;
import org.iphoto.model.ImmichImportOverviewDTO;
import org.iphoto.model.ImmichSettingsDTO;
import org.iphoto.model.request.ImmichSettingsRequest;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.UserService;
import org.iphoto.service.immich.ImmichAlbumImportService;
import org.iphoto.service.immich.ImmichImportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

// Per-user migration feature (see specification.md / TODO.md): each user connects their own
// self-hosted Immich server (baseUrl + API key, see /settings below) and imports photos,
// metadata, albums and album-level shared links from it, day by day. No role gating - any
// authenticated user manages and imports their own Immich connection; SecurityConfig's
// anyRequest().authenticated() rule already covers this path (deliberately NOT under
// /api/admin/**, unlike before).
@RestController
@RequestMapping("/api/immich")
@Slf4j
public class ImmichImportController extends AbstractBaseController {

    private final ImmichImportService immichImportService;
    private final ImmichAlbumImportService immichAlbumImportService;
    private final UserService userService;

    public ImmichImportController(UserRepository userRepository, ImmichImportService immichImportService,
                                   ImmichAlbumImportService immichAlbumImportService, UserService userService) {
        super(userRepository);
        this.immichImportService = immichImportService;
        this.immichAlbumImportService = immichAlbumImportService;
        this.userService = userService;
    }

    @GetMapping("/settings")
    public ImmichSettingsDTO getSettings() {
        return toSettingsDTO(getCurrentUser());
    }

    @PutMapping("/settings")
    public ImmichSettingsDTO updateSettings(@RequestBody ImmichSettingsRequest request) {
        UserEntity user = getCurrentUser();
        return toSettingsDTO(userService.updateImmichSettings(user.getId(), request.baseUrl(), request.apiKey()));
    }

    @DeleteMapping("/settings")
    public ImmichSettingsDTO clearSettings() {
        UserEntity user = getCurrentUser();
        return toSettingsDTO(userService.clearImmichSettings(user.getId()));
    }

    @PostMapping("/sync-days")
    public ImmichImportOverviewDTO syncDays() {
        UserEntity user = getCurrentUser();
        immichImportService.syncDayIndex(user, currentTimezoneOffset());
        return immichImportService.getOverview(user);
    }

    @GetMapping("/days")
    public ImmichImportOverviewDTO getDays() {
        return immichImportService.getOverview(getCurrentUser());
    }

    @GetMapping("/days/{day}")
    public ImmichImportDayDTO getDay(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return immichImportService.getDay(getCurrentUser(), day);
    }

    // Also the retry mechanism for a PARTIAL/FAILED day - re-triggering is safe/idempotent, see
    // ImmichImportService.triggerDayImport.
    @PostMapping("/days/{day}/import")
    public ImmichImportDayDTO importDay(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return immichImportService.triggerDayImport(day, getCurrentUser(), currentTimezoneOffset());
    }

    @PostMapping("/albums/import")
    public ImmichAlbumImportSummaryDTO importAlbums() {
        return immichAlbumImportService.triggerAlbumImport(getCurrentUser());
    }

    private ImmichSettingsDTO toSettingsDTO(UserEntity user) {
        return new ImmichSettingsDTO(user.getImmichBaseUrl(), userService.resolveImmichCredentials(user).isPresent());
    }

    private static String currentTimezoneOffset() {
        return OffsetDateTime.now().getOffset().getId();
    }
}
