package uy.pensiones.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionMedia;
import uy.pensiones.repo.PensionMediaRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.storage.MediaStorage;
import uy.pensiones.storage.MediaStorageKeys;

import java.io.IOException;
import java.util.*;

@Service
public class PensionMediaService {

    private final PensionMediaRepository repo;
    private final PensionRepository pensions;
    private final PensionPublicationService publication;
    private final OwnerEntitlementService entitlements;
    private final PensionImageVariantService imageVariants;
    private final PensionMediaSecurityService mediaSecurity;
    private final MediaTransactionLifecycle mediaTransactions;

    private final MediaStorage storage;

    public PensionMediaService(PensionMediaRepository repo, PensionRepository pensions,
                               PensionPublicationService publication,
                               OwnerEntitlementService entitlements,
                               PensionImageVariantService imageVariants,
                               PensionMediaSecurityService mediaSecurity,
                               MediaTransactionLifecycle mediaTransactions,
                               MediaStorage storage) {
        this.repo = repo;
        this.pensions = pensions;
        this.publication = publication;
        this.entitlements = entitlements;
        this.imageVariants = imageVariants;
        this.mediaSecurity = mediaSecurity;
        this.mediaTransactions = mediaTransactions;
        this.storage = storage;
    }

    @Transactional
    public List<PensionMedia> upload(Pension pension, List<MultipartFile> files) throws IOException {
        List<MultipartFile> selected = files == null ? List.of() : files.stream()
                .filter(Objects::nonNull)
                .filter(file -> !file.isEmpty())
                .toList();
        mediaSecurity.requireValidFileCount(selected.size());

        List<PreparedUpload> prepared = new ArrayList<>(selected.size());
        int photosToAdd = 0;
        int videosToAdd = 0;
        for (MultipartFile file : selected) {
            PensionMediaSecurityService.DetectedMedia detected = mediaSecurity.inspect(file);
            prepared.add(new PreparedUpload(file, detected));
            if (detected.kind() == PensionMediaSecurityService.MediaKind.IMAGE) photosToAdd++;
            else videosToAdd++;
        }
        entitlements.requireCanAddMedia(pension.getId(), photosToAdd, videosToAdd);

        List<PensionMedia> saved = new ArrayList<>();
        List<String> createdKeys = new ArrayList<>();
        List<String> createdImages = new ArrayList<>();
        Long pensionId = pension.getId();
        mediaTransactions.afterRollback(() -> cleanupCreated(pensionId, createdKeys, createdImages));

        int next = Optional.ofNullable(repo.lastSort(pension.getId())).orElse(0);
        boolean hasCover = repo.findByPensionIdOrderBySortOrderAscIdAsc(pension.getId()).stream()
                .anyMatch(PensionMedia::isCover)
                || (pension.getFeaturedImage() != null && !pension.getFeaturedImage().isBlank());

        try {
            for (PreparedUpload upload : prepared) {
                MultipartFile file = upload.file();
                PensionMediaSecurityService.DetectedMedia detected = upload.detected();
                boolean isImg = detected.kind() == PensionMediaSecurityService.MediaKind.IMAGE;
                String filename = UUID.randomUUID() + detected.extension();
                String storageKey = MediaStorageKeys.original(pensionId, filename);
                createdKeys.add(storageKey);

                Integer width = null;
                Integer height = null;
                long storedSize;
                if (isImg) {
                    PensionMediaSecurityService.NormalizedImageData normalized = mediaSecurity.normalizeImage(file, detected);
                    width = normalized.width();
                    height = normalized.height();
                    storedSize = storage.write(storageKey, normalized.bytes()).sizeBytes();
                    createdImages.add(filename);
                } else {
                    try (var input = file.getInputStream()) {
                        storedSize = storage.write(storageKey, input).sizeBytes();
                    }
                }

                PensionMedia media = new PensionMedia();
                media.setPension(pension);
                media.setKind(isImg ? PensionMedia.Kind.IMAGE : PensionMedia.Kind.VIDEO);
                media.setFilename(filename);
                media.setUrl(storage.deliveryUrl(storageKey));
                media.setMimeType(detected.mimeType());
                media.setSizeBytes(storedSize);
                media.setWidth(width);
                media.setHeight(height);
                media.setSortOrder(++next);
                if (isImg && !hasCover) {
                    media.setCover(true);
                    pension.setFeaturedImage(filename);
                    hasCover = true;
                }

                saved.add(repo.save(media));
                if (isImg) imageVariants.preGenerateListingVariants(pension.getId(), filename);
            }
            pensions.save(pension);
            return saved;
        } catch (IOException | RuntimeException ex) {
            cleanupCreated(pensionId, createdKeys, createdImages);
            throw ex;
        }
    }

    private record PreparedUpload(MultipartFile file, PensionMediaSecurityService.DetectedMedia detected) {}

    @Transactional
    public PensionMedia addYoutubeLink(Pension pension, String url) {
        String normalizedUrl = mediaSecurity.normalizeExternalVideoUrl(url);
        entitlements.requireCanAddMedia(pension.getId(), 0, 1);
        int next = Optional.ofNullable(repo.lastSort(pension.getId())).orElse(0) + 1;
        PensionMedia m = new PensionMedia();
        m.setPension(pension);
        m.setKind(PensionMedia.Kind.YOUTUBE);
        m.setUrl(normalizedUrl);
        m.setSortOrder(next);
        return repo.save(m);
    }

    public void reorder(Long pensionId, List<Long> orderedIds) {
        List<PensionMedia> list = repo.findByPensionIdOrderBySortOrderAscIdAsc(pensionId);
        Map<Long, PensionMedia> byId = new HashMap<>();
        list.forEach(x -> byId.put(x.getId(), x));
        int i = 1;
        for (Long id : orderedIds) {
            PensionMedia m = byId.get(id);
            if (m != null) { m.setSortOrder(i++); repo.save(m); }
        }
    }

    public PensionMedia setCover(Long pensionId, Long mediaId, boolean cover) {
        PensionMedia target = repo.findById(mediaId)
                .filter(m -> m.getPension().getId().equals(pensionId))
                .orElseThrow();

        if (cover && target.getKind() != PensionMedia.Kind.IMAGE) {
            throw new IllegalArgumentException("La portada debe ser una imagen.");
        }

        Pension pension = pensions.findById(pensionId).orElseThrow();
        if (!cover
                && pension.getStatus() == uy.pensiones.enums.PensionStatus.PUBLISHED
                && Objects.equals(pension.getFeaturedImage(), target.getFilename())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Una pensión publicada debe conservar una imagen de portada. Pausa la publicación antes de quitarla."
            );
        }

        List<PensionMedia> list = repo.findByPensionIdOrderBySortOrderAscIdAsc(pensionId);
        for (PensionMedia m : list) {
            boolean c = m.getId().equals(mediaId) && cover;
            if (m.isCover() != c) { m.setCover(c); repo.save(m); }
        }

        if (cover) {
            pension.setFeaturedImage(target.getFilename());
        } else if (Objects.equals(pension.getFeaturedImage(), target.getFilename())) {
            pension.setFeaturedImage(null);
        }
        pensions.save(pension);

        return repo.findById(mediaId).orElseThrow();
    }

    @Transactional
    public void delete(Long pensionId, Long mediaId) throws IOException {
        PensionMedia media = repo.findByIdAndPensionId(mediaId, pensionId).orElseThrow();
        Pension pension = media.getPension();
        publication.requireCanDeleteMedia(pension, media);
        boolean wasFeatured = media.isCover()
                || Objects.equals(pension.getFeaturedImage(), media.getFilename());

        String storedFilename = media.getFilename();
        String storedKey = storedFilename == null ? null : MediaStorageKeys.original(pensionId, storedFilename);
        repo.delete(media);

        if (wasFeatured) {
            var replacement = repo.findByPensionIdOrderBySortOrderAscIdAsc(pension.getId()).stream()
                    .filter(m -> m.getKind() == PensionMedia.Kind.IMAGE)
                    .findFirst();

            if (replacement.isPresent()) {
                PensionMedia next = replacement.get();
                next.setCover(true);
                repo.save(next);
                pension.setFeaturedImage(next.getFilename());
            } else {
                pension.setFeaturedImage(null);
            }
            pensions.save(pension);
        }

        if (storedKey != null && storedFilename != null) {
            mediaTransactions.afterCommit(() -> deleteStoredFile(pensionId, storedKey, storedFilename));
        }
    }

    private void cleanupCreated(Long pensionId, List<String> createdKeys, List<String> createdImages) {
        for (String key : List.copyOf(createdKeys)) {
            try { storage.delete(key); } catch (Exception ignored) {}
        }
        for (String filename : List.copyOf(createdImages)) {
            imageVariants.deleteVariants(pensionId, filename);
        }
    }

    private void deleteStoredFile(Long pensionId, String storedKey, String storedFilename) {
        try { storage.delete(storedKey); } catch (Exception ignored) {}
        imageVariants.deleteVariants(pensionId, storedFilename);
    }

}
