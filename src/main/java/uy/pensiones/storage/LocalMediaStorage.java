package uy.pensiones.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.media-storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalMediaStorage implements MediaStorage {
    private final Path root;

    public LocalMediaStorage(@Value("${app.media-root:./uploads}") String mediaRoot) {
        this.root = Paths.get(mediaRoot).toAbsolutePath().normalize();
    }

    @Override
    public String provider() {
        return "local";
    }

    @Override
    public StoredObject write(String key, InputStream input) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return metadata(key, target);
    }

    @Override
    public Resource resource(String key) throws IOException {
        Path target = resolve(key);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(key);
        return new FileSystemResource(target);
    }

    @Override
    public boolean exists(String key) {
        try {
            return Files.isRegularFile(resolve(key), LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public Instant lastModified(String key) throws IOException {
        return Files.getLastModifiedTime(resolve(key), LinkOption.NOFOLLOW_LINKS).toInstant();
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    @Override
    public List<StoredObject> list(String prefix) throws IOException {
        String normalizedPrefix = prefix == null || prefix.isBlank() ? "" : MediaStorageKeys.normalizeKey(trimTrailingSlash(prefix));
        Path start = normalizedPrefix.isBlank() ? root : resolve(normalizedPrefix);
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<StoredObject> result = new ArrayList<>();
        try (var paths = Files.walk(start)) {
            for (Path path : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String key = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
                result.add(metadata(key, path));
            }
        }
        return result;
    }

    @Override
    public String deliveryUrl(String key) {
        return "/media/" + MediaStorageKeys.normalizeKey(key);
    }

    @Override
    public StorageStatus health(long minUsableBytes) {
        try {
            Files.createDirectories(root);
            if (!Files.isDirectory(root) || !Files.isWritable(root)) {
                return new StorageStatus(false, null, "media-storage-not-writable");
            }
            long usable = Files.getFileStore(root).getUsableSpace();
            if (usable < minUsableBytes) return new StorageStatus(false, usable, "media-storage-low-space");
            return new StorageStatus(true, usable, null);
        } catch (Exception ex) {
            return new StorageStatus(false, null, "media-storage-unavailable");
        }
    }

    @Override
    public Optional<String> resourceLocation() {
        String location = root.toUri().toString();
        return Optional.of(location.endsWith("/") ? location : location + "/");
    }

    public Path root() {
        return root;
    }

    private StoredObject metadata(String key, Path path) throws IOException {
        return new StoredObject(MediaStorageKeys.normalizeKey(key), Files.size(path),
                Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant());
    }

    private Path resolve(String key) {
        String normalized = MediaStorageKeys.normalizeKey(key);
        Path target = root.resolve(normalized).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Clave fuera del almacenamiento de media");
        return target;
    }

    private String trimTrailingSlash(String value) {
        String result = value.replace('\\', '/');
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
