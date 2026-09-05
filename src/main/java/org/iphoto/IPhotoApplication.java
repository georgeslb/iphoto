package org.iphoto;

import lombok.extern.slf4j.Slf4j;
import org.iphoto.properties.AdminProperties; // Import AdminProperties
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.UserService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication
@EnableScheduling
@EnableRetry(proxyTargetClass=true)
public class IPhotoApplication {

    public static void main(String[] args) {
        // PyTorch's CPU backend defaults to using every available core for the tensor math inside
        // a single inference call (intra-op parallelism), independent of how many Java-level
        // worker threads are running face-detection tasks - so even one task in flight can peg the
        // whole machine. Must be set before the DJL PyTorch engine initializes (its first
        // Criteria.loadModel() call, in FaceDetectionConfiguration), so as early as possible here.
        System.setProperty("ai.djl.pytorch.num_threads", "8");
        SpringApplication.run(IPhotoApplication.class, args);
    }

    @Bean
    CommandLineRunner initDatabase(UserRepository userRepository, UserService userService, AdminProperties adminProperties) {
        return args -> {
            if (userRepository.findByUsername(adminProperties.getUsername()).isEmpty()) {
                // Routed through UserService.createUser (not a raw UserEntity save) so the
                // bootstrapped admin gets a default MAIN space too, exactly like any user created
                // via the admin-facing "create user" API — a fresh install used to leave the
                // bootstrapped admin with no space at all, which broke upload/albums/etc.
                // (SpaceService.getDefaultSpaceForUser(admin) would return null) until a space
                // was created some other way. Found while writing integration tests against a
                // genuinely fresh database, where this had never been exercised before.
                userService.createUser(adminProperties.getUsername(), adminProperties.getPassword(), adminProperties.getRole());
            }
        };
    }

    // Backfills the groupedDay column (day-index browsing) for photos that existed before it was
    // introduced - ddl-auto:update only adds the new column on boot, it never populates existing
    // rows. Idempotent (PhotoRepository.backfillGroupedDay is itself IS NULL-guarded), so this is
    // a cheap no-op on every boot after the first; a plain CommandLineRunner bean rather than a
    // new ApplicationRunner class, matching initDatabase above as the existing precedent for
    // one-off startup tasks in this app.
    @Bean
    CommandLineRunner backfillGroupedDay(PhotoRepository photoRepository) {
        return args -> {
            int updated = photoRepository.backfillGroupedDay();
            if (updated > 0) {
                log.info("Backfilled groupedDay for {} existing photo(s)", updated);
            }
        };
    }
}