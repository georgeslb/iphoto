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

    // Library's "N months/years ago" bar (see PhotoService.getMemories) - one candidate window per
    // configured offset, searched today minus that many months. Configurable here rather than
    // hardcoded so the set of offsets can change without a code change.
    @Data
    public static class Memories {
        // Each entry is a plain integer ("6" - exact day, no tolerance) or that integer plus one
        // or both signed day-tolerance tokens in either order ("6-2d+3d" == "6+3d-2d"): "-Nd"
        // widens the search N days earlier than the anchor day, "+Nd" widens it N days later -
        // see MemoryOffset#parse for the exact grammar. Useful for an anchor day that rarely has a
        // photo taken on it exactly, without needing an exact hit every time.
        List<String> offsets = List.of("1", "6", "12", "36", "60", "120");
        // Up to this many random photos per offset, auto-cycled in the frontend's one block
        // instead of a single static photo - fewer than this if the window has fewer photos.
        int photosPerBlock = 5;
    }

    // Immich connection info moved to a per-user setting (see UserEntity.immichBaseUrl/
    // immichApiKeyEncrypted, UserService#resolveImmichCredentials) - no more global
    // env-var-configured server here.
}
