package uy.pensiones.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StudyCenterCatalogUpdateRequest(
        @NotBlank @Size(max = 140) String name,
        @Size(max = 100) String city,
        @Size(max = 220) String address,
        @Size(max = 2) @Pattern(regexp = "^$|^[A-Za-z]{2}$", message = "El país debe ser un código ISO de 2 letras") String countryCode,
        Double lat,
        Double lng,
        Boolean verified,
        @Size(max = 1500, message = "El motivo no puede superar 1500 caracteres") String reason
) {}
