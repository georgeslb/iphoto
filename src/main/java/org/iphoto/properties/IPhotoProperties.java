package org.iphoto.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "iphoto")
@Data
public class IPhotoProperties {
    Storage storage;
    Memories memories = new Memories();

    @Data
    public static class Storage {
        String immichTemp;
        String photos;
        String thumbnails;
        String temp;
        String chunks;
    }

    // Library's "N months/years ago" bar (see PhotoService.getMemories) - one candidate day per
    // configured offset, computed as today minus that many months. Configurable here rather than
    // hardcoded so the set of offsets can change without a code change.
    @Data
    public static class Memories {
        List<Integer> monthsAgo = List.of(1, 6, 12, 36, 60, 120);
        // Up to this many random photos per offset, auto-cycled in the frontend's one block
        // instead of a single static photo - fewer than this if the day itself has fewer photos.
        int photosPerBlock = 5;
    }

    // Immich connection info moved to a per-user setting (see UserEntity.immichBaseUrl/
    // immichApiKeyEncrypted, UserService#resolveImmichCredentials) - no more global
    // env-var-configured server here.
}
