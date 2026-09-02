package org.iphoto;

import org.iphoto.properties.AdminProperties; // Import AdminProperties
import org.iphoto.repository.UserRepository;
import org.iphoto.service.UserService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableRetry
public class IPhotoApplication {
    public static void main(String[] args) {
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
}