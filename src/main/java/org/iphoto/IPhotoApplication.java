package org.iphoto;

import org.iphoto.entities.UserEntity;
import org.iphoto.properties.AdminProperties; // Import AdminProperties
import org.iphoto.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootApplication
@EnableScheduling
@EnableRetry
public class IPhotoApplication {
    public static void main(String[] args) {
        SpringApplication.run(IPhotoApplication.class, args);
    }

    @Bean
    CommandLineRunner initDatabase(UserRepository userRepository, PasswordEncoder passwordEncoder, AdminProperties adminProperties) { // Inject AdminProperties
        return args -> {
            if (userRepository.findByUsername(adminProperties.getUsername()).isEmpty()) {
                UserEntity admin = new UserEntity();
                admin.setUsername(adminProperties.getUsername());
                admin.setPassword(passwordEncoder.encode(adminProperties.getPassword()));
                admin.setRole(adminProperties.getRole());
                userRepository.save(admin);
            }
        };
    }
}