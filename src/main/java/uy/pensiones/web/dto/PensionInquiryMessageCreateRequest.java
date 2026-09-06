package uy.pensiones.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PensionInquiryMessageCreateRequest(
        @NotBlank(message = "Escribe un mensaje")
        @Size(max = 2000, message = "El mensaje no puede superar los 2000 caracteres")
        String message
) {}
