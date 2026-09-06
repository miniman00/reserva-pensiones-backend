package uy.pensiones.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uy.pensiones.config.MediaStorageProperties;
import uy.pensiones.repo.PensionMediaRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.storage.MediaStorage;
import uy.pensiones.storage.MediaStorageKeys;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Service
public class MediaStorageMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(MediaStorageMaintenanceService.class);

    private final PensionMediaRepository media;
    private final PensionRepository pensions;
    private final PensionImageVariantService variants;
    private final MediaStorageProperties properties;
    private final MediaStorage storage;

    public MediaStorageMaintenanceService(PensionMediaRepository media,
                                          PensionRepository pensions,
                                          PensionImageVariantService variants,
                                          MediaStorageProperties properties,
                                          MediaStorage storage) {
        this.media = media;
        this.pensions = pensions;
        this.variants = variants;
        this.properties = properties;
        this.storage = storage;
    }

    @Scheduled(
            cron = "${app.media-maintenance.cleanup-cron:0 35 3 * * *}",
            zone = "${app.media-maintenance.cleanup-zone:UTC}"
    )
    public void scheduledReconcile() {
        reconcile(properties.isCleanupEnabled());
    }

    public Result reconcile(boolean deleteOrphans) {
        Set<String> referenced = referencedOriginalKeys();
        Instant cutoff = Instant.now().minus(properties.getOrphanGrace());
        long scanned = 0;
        long missing = 0;
        long orphaned = 0;
        long deleted = 0;

        for (String key : referenced) {
            if (!storage.exists(key)) missing++;
        }

        try {
            for (MediaStorage.StoredObject object : storage.list(MediaStorageKeys.allPensionsPrefix())) {
                scanned++;
                String key = object.key();
                StoredKind kind = classify(key, referenced);
                if (kind == StoredKind.REFERENCED) continue;
                if (object.lastModified() == null || !object.lastModified().isBefore(cutoff)) continue;

                orphaned++;
                if (deleteOrphans && deleteObject(key, kind)) deleted++;
            }
        } catch (IOException ex) {
            log.warn("No se pudo recorrer el almacenamiento de media provider={}: {}",
                    storage.provider(), ex.getMessage(), ex);
        }

        if (missing > 0 || orphaned > 0) {
            log.warn("Reconciliación de media: provider={}, scanned={}, missingReferenced={}, orphaned={}, deleted={}, cleanupEnabled={}",
                    storage.provider(), scanned, missing, orphaned, deleted, deleteOrphans);
        } else {
            log.info("Reconciliación de media OK: provider={}, scanned={}, referenced={}",
                    storage.provider(), scanned, referenced.size());
        }
        return new Result(scanned, missing, orphaned, deleted);
    }

    private Set<String> referencedOriginalKeys() {
        Set<String> referenced = new HashSet<>();
        for (PensionMediaRepository.StoredFileReference ref : media.findStoredFileReferences()) {
            addReference(referenced, ref.getPensionId(), ref.getFilename());
        }
        for (PensionRepository.FeaturedImageReference ref : pensions.findFeaturedImageReferences()) {
            addReference(referenced, ref.getPensionId(), ref.getFilename());
        }
        return referenced;
    }

    private void addReference(Set<String> referenced, Long pensionId, String filename) {
        if (pensionId == null || filename == null || filename.isBlank()) return;
        try {
            referenced.add(MediaStorageKeys.original(pensionId, filename));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private StoredKind classify(String key, Set<String> referenced) {
        if (referenced.contains(key)) return StoredKind.REFERENCED;
        if (key.contains("/.variants/")) {
            String original = originalKeyForVariant(key);
            return original != null && referenced.contains(original) && storage.exists(original)
                    ? StoredKind.REFERENCED
                    : StoredKind.VARIANT;
        }
        return StoredKind.ORIGINAL;
    }

    private String originalKeyForVariant(String key) {
        String marker = "/.variants/";
        int markerIndex = key.indexOf(marker);
        if (markerIndex <= 0) return null;
        int filenameIndex = key.indexOf('/', markerIndex + marker.length());
        if (filenameIndex < 0 || filenameIndex + 1 >= key.length()) return null;
        String variantFilename = key.substring(filenameIndex + 1);
        if (!variantFilename.endsWith(".jpg") || variantFilename.length() <= 4) return null;
        String originalFilename = variantFilename.substring(0, variantFilename.length() - 4);
        return key.substring(0, markerIndex + 1) + originalFilename;
    }

    private boolean deleteObject(String key, StoredKind kind) {
        try {
            storage.delete(key);
            if (kind == StoredKind.ORIGINAL) {
                OriginalKey original = parseOriginal(key);
                if (original != null) variants.deleteVariants(original.pensionId(), original.filename());
            }
            return true;
        } catch (Exception ex) {
            log.warn("No se pudo eliminar media huérfana provider={}, key={}: {}",
                    storage.provider(), key, ex.getMessage());
            return false;
        }
    }

    private OriginalKey parseOriginal(String key) {
        String[] parts = key.split("/");
        if (parts.length != 3 || !"pensions".equals(parts[0]) || ".variants".equals(parts[2])) return null;
        try {
            return new OriginalKey(Long.valueOf(parts[1]), MediaStorageKeys.safeFilename(parts[2]));
        } catch (Exception ignored) {
            return null;
        }
    }

    private enum StoredKind { REFERENCED, ORIGINAL, VARIANT }
    private record OriginalKey(Long pensionId, String filename) {}
    public record Result(long scannedFiles, long missingReferencedFiles, long orphanedFiles, long deletedFiles) {}
}
