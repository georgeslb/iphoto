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
        String photos;
        String thumbnails;
        String temp;
        String chunks;
    }
}
