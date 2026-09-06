package uy.pensiones.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.security.PensionMediaAccessService;
import uy.pensiones.service.PensionImageVariantService;
import uy.pensiones.service.PublicTrafficProtectionService;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/public/media/pensions")
public class PublicPensionMediaController {

    private final PensionImageVariantService images;
    private final PensionMediaAccessService access;
    private final PublicTrafficProtectionService traffic;

    public PublicPensionMediaController(PensionImageVariantService images,
                                        PensionMediaAccessService access,
                                        PublicTrafficProtectionService traffic) {
        this.images = images;
        this.access = access;
        this.traffic = traffic;
    }

    @GetMapping("/{pensionId}/{variant}/{filename:.+}")
    public ResponseEntity<org.springframework.core.io.Resource> imageVariant(
            HttpServletRequest servletRequest,
            @PathVariable Long pensionId,
            @PathVariable String variant,
            @PathVariable String filename) throws IOException {
        if (!traffic.allowMedia(servletRequest)) {
            return ResponseEntity.status(429).header("Retry-After", "60").build();
        }

        PensionImageVariantService.Variant selected = PensionImageVariantService.Variant.fromPath(variant);
        if (selected == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Imagen no encontrada");
        }

        PensionMediaAccessService.AccessLevel level = access.accessLevel(servletRequest, pensionId, filename);
        if (level == PensionMediaAccessService.AccessLevel.DENIED) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Imagen no encontrada");
        }

        var image = images.variantAuthorized(pensionId, filename, selected);
        CacheControl cache = level == PensionMediaAccessService.AccessLevel.PUBLIC
                ? CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic().mustRevalidate()
                : CacheControl.noStore().cachePrivate();
        return ResponseEntity.ok()
                .cacheControl(cache)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header("Content-Type", image.contentType())
                .body(image.resource());
    }
}
