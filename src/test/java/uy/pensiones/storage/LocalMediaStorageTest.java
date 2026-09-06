package uy.pensiones.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalMediaStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void writeReadListAndDeleteUseStableLogicalKeys() throws Exception {
        LocalMediaStorage storage = new LocalMediaStorage(tempDir.toString());
        String key = MediaStorageKeys.original(42L, "photo.jpg");

        var stored = storage.write(key, new ByteArrayInputStream("pixels".getBytes(StandardCharsets.UTF_8)));

        assertThat(stored.key()).isEqualTo("pensions/42/photo.jpg");
        assertThat(storage.exists(key)).isTrue();
        assertThat(storage.resource(key).getContentAsString(StandardCharsets.UTF_8)).isEqualTo("pixels");
        assertThat(storage.list("pensions/")).extracting(MediaStorage.StoredObject::key).containsExactly(key);
        assertThat(storage.deliveryUrl(key)).isEqualTo("/media/pensions/42/photo.jpg");
        assertThat(storage.resourceLocation()).isPresent();

        storage.delete(key);
        assertThat(storage.exists(key)).isFalse();
    }

    @Test
    void rejectsTraversalOutsideConfiguredRoot() {
        LocalMediaStorage storage = new LocalMediaStorage(tempDir.toString());
        assertThatThrownBy(() -> storage.write("../secret.txt", new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.resource("pensions/42/../../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void healthReportsProviderAndCapacity() {
        LocalMediaStorage storage = new LocalMediaStorage(tempDir.toString());
        var status = storage.health(32L * 1024 * 1024);
        assertThat(status.available()).isTrue();
        assertThat(status.usableBytes()).isPositive();
        assertThat(storage.provider()).isEqualTo("local");
    }
}
