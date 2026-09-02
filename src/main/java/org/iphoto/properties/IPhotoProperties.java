package org.iphoto.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "iphoto")
@Data
public class IPhotoProperties {
    Storage storage;

    @Data
    public static class Storage {
        String immichTemp;
        String photos;
        String thumbnails;
        String temp;
        String chunks;
    }

    // Immich connection info moved to a per-user setting (see UserEntity.immichBaseUrl/
    // immichApiKeyEncrypted, UserService#resolveImmichCredentials) - no more global
    // env-var-configured server here.
}
