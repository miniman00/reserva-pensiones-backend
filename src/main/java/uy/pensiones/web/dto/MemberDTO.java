package uy.pensiones.web.dto;

import uy.pensiones.enums.PensionRole;
import uy.pensiones.model.PensionMember;

public record MemberDTO(Long id, Long userId, String name, String email, PensionRole role) {
    public static MemberDTO of(PensionMember m) {
        return new MemberDTO(m.getId(), m.getUser().getId(), m.getUser().getName(), m.getUser().getEmail(), m.getRole());
    }
}
