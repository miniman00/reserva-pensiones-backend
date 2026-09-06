package uy.pensiones.storage;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Almacenamiento de objetos multimedia independiente del proveedor físico.
 *
 * Las claves son siempre relativas y usan '/'. La capa de negocio no debe
 * construir Paths ni conocer si detrás existe un filesystem, S3/R2/MinIO, etc.
 */
public interface MediaStorage {

    record StoredObject(String key, long sizeBytes, Instant lastModified) {}
    record StorageStatus(boolean available, Long usableBytes, String reason) {}

    String provider();

    StoredObject write(String key, InputStream input) throws IOException;

    default StoredObject write(String key, byte[] data) throws IOException {
        try (var input = new java.io.ByteArrayInputStream(data)) {
            return write(key, input);
        }
    }

    Resource resource(String key) throws IOException;

    boolean exists(String key);

    Instant lastModified(String key) throws IOException;

    void delete(String key) throws IOException;

    List<StoredObject> list(String prefix) throws IOException;

    /**
     * URL usada por el cliente para el archivo original. Una implementación de
     * object storage puede devolver un endpoint proxy/CDN sin cambiar DTOs.
     */
    String deliveryUrl(String key);

    StorageStatus health(long minUsableBytes);

    /**
     * Solo la implementación local expone una ubicación para ResourceHandler.
     * Los proveedores remotos dejan vacío este valor.
     */
    default Optional<String> resourceLocation() {
        return Optional.empty();
    }
}
