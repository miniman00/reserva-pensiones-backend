package uy.pensiones.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaStorageKeysTest {

    @Test
    void buildsProviderIndependentKeys() {
        assertThat(MediaStorageKeys.original(7L, "photo.png"))
                .isEqualTo("pensions/7/photo.png");
        assertThat(MediaStorageKeys.variant(7L, "photo.png", "card"))
                .isEqualTo("pensions/7/.variants/card/photo.png.jpg");
    }

    @Test
    void rejectsPathTraversalAndNestedFilenames() {
        assertThatThrownBy(() -> MediaStorageKeys.original(7L, "../photo.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaStorageKeys.original(7L, "folder/photo.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaStorageKeys.normalizeKey("pensions/7/../../secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
