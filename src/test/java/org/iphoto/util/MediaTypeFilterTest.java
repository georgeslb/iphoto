package org.iphoto.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Plain unit test - no Spring context needed, this is a pure string -> Boolean mapping shared by
// PhotoController/AlbumController's ?type= query param.
class MediaTypeFilterTest {

    @Test
    void videoMapsToTrue() {
        assertThat(MediaTypeFilter.resolveIsVideo("video")).isTrue();
        assertThat(MediaTypeFilter.resolveIsVideo("VIDEO")).isTrue();
        assertThat(MediaTypeFilter.resolveIsVideo("Video")).isTrue();
    }

    @Test
    void photoMapsToFalse() {
        assertThat(MediaTypeFilter.resolveIsVideo("photo")).isFalse();
        assertThat(MediaTypeFilter.resolveIsVideo("PHOTO")).isFalse();
    }

    @Test
    void nullOrUnrecognizedMapsToNullMeaningEveryType() {
        assertThat(MediaTypeFilter.resolveIsVideo(null)).isNull();
        assertThat(MediaTypeFilter.resolveIsVideo("")).isNull();
        assertThat(MediaTypeFilter.resolveIsVideo("all")).isNull();
        assertThat(MediaTypeFilter.resolveIsVideo("bogus")).isNull();
    }
}
