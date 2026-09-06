package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.model.Pension;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.PensionViewRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

@Service
public class PensionViewService {

    private final PensionRepository pensions;
    private final PensionViewRepository views;

    public PensionViewService(PensionRepository pensions, PensionViewRepository views) {
        this.pensions = pensions;
        this.views = views;
    }

    public void record(Long pensionId, String visitorKey) {
        Pension pension = pensions.findWithOwnerById(pensionId)
                .filter(item -> item.getStatus() == PensionStatus.PUBLISHED
                        && !Boolean.TRUE.equals(item.getModerationBlocked())
                        && !ownerSuspended(item))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada"));

        String normalized = visitorKey == null ? "" : visitorKey.trim();
        if (normalized.length() < 16 || normalized.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador de visita inválido");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        views.insertIgnore(
                pension.getId(),
                sha256(normalized),
                LocalDate.now(ZoneOffset.UTC),
                now
        );
    }

    private boolean ownerSuspended(Pension pension) {
        var owner = pension.getOwner() != null ? pension.getOwner() : pension.getCreatedBy();
        return owner == null || owner.isSuspended();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
