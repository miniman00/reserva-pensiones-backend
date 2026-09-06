package uy.pensiones.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminReasonRequest(
        @NotBlank(message = "Debes indicar el motivo de la acción administrativa")
        @Size(max = 1500, message = "El motivo no puede superar 1500 caracteres")
        String reason
) {}
