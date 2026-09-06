package uy.pensiones.web.dto;

import java.time.OffsetDateTime;

/** Señales públicas verificables. No representan verificación de identidad ni inspección del inmueble. */
public record PublicPensionTrustDTO(
        boolean ownerEmailVerified,
        OffsetDateTime ownerAccountCreatedAt,
        OffsetDateTime listingCreatedAt
) {}
