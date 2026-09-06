package uy.pensiones.web.dto;

import uy.pensiones.enums.InviteStatus;
import uy.pensiones.enums.PensionRole;
import uy.pensiones.model.PensionInvite;

import java.time.OffsetDateTime;

public record InviteDTO(Long id, String email, PensionRole role, InviteStatus status, String token,
                        OffsetDateTime expiresAt) {
    public static InviteDTO of(PensionInvite i) {
        return new InviteDTO(i.getId(), i.getEmail(), i.getRole(), i.getStatus(), i.getToken(), i.getExpiresAt());
    }
}
