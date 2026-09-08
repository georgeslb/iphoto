package org.iphoto.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import tools.jackson.databind.JacksonModule;
import tools.jackson.datatype.hibernate5.jakarta.Hibernate5JakartaModule;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

// VIA_DTO pins every Page<T>-returning controller method (AlbumController.getAlbumPhotosForDay,
// FaceController.getPersons/getPersonFaces/getPersonPhotosForDay, SpaceController.getSpacePhotos,
// AlbumShareController.getAlbumPhotos) to Spring Data's stable {content, page: {size, number,
// totalElements, totalPages}} DTO shape. Without this, spring-data-commons' default
// (PageSerializationMode.DIRECT) serializes the raw PageImpl instead - the verbose,
// version-unstable shape (pageable/sort/numberOfElements/empty/...) that logs "Serializing
// PageImpl instances as-is is not supported" on first use (confirmed live: this app was hitting
// exactly that warning before this was added). The day-index endpoints' own frontend consumer
// (useDayIndexedPhotos.js's normalizePage) already expected this exact DTO shape - useSharedAlbumPhotos.js
// (the one active consumer of a raw Page<T> endpoint, AlbumShareController.getAlbumPhotos) was
// updated alongside this to match.
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
@Configuration
public class JsonConfiguration {
    @Bean
    public JacksonModule hibernateModule() {
        return new Hibernate5JakartaModule();
    }
}