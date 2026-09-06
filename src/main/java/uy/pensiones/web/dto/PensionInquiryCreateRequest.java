package uy.pensiones.web.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import uy.pensiones.enums.InquiryRoomType;
import uy.pensiones.service.LegalDocuments;

import java.time.LocalDate;
import java.util.regex.Pattern;

public record PensionInquiryCreateRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(min = 2, max = 120, message = "El nombre debe tener entre 2 y 120 caracteres")
        String name,

        @Email(message = "El email no es válido")
        @Size(max = 190, message = "El email no puede superar 190 caracteres")
        String email,

        @Size(max = 40, message = "El teléfono no puede superar 40 caracteres")
        String phone,

        InquiryRoomType roomType,

        @FutureOrPresent(message = "La fecha estimada de ingreso no puede estar en el pasado")
        LocalDate moveInDate,

        @Size(max = 2000, message = "El mensaje no puede superar 2000 caracteres")
        String message,

        // Honeypot: el formulario real nunca completa este campo.
        @Size(max = 160, message = "Dato inválido")
        String website,

        // Identificador aleatorio local del navegador. No contiene datos personales.
        @Size(min = 16, max = 120, message = "Identificador de visitante inválido")
        String visitorKey,

        // Identificador de la exposición comercial devuelta por el listado público.
        // Es opcional y el servidor vuelve a validar pensión + vigencia antes de atribuirla.
        @Positive(message = "Identificador de destacado inválido")
        Long featuredPromotionId,

        @NotNull(message = "Debes aceptar los Términos de Uso")
        @AssertTrue(message = "Debes aceptar los Términos de Uso")
        Boolean termsAccepted,

        @NotNull(message = "Debes aceptar la Política de Privacidad")
        @AssertTrue(message = "Debes aceptar la Política de Privacidad y autorizar el uso de tus datos para gestionar la consulta")
        Boolean privacyAccepted,

        @NotBlank(message = "Versión de Términos requerida")
        @Size(max = 20)
        String termsVersion,

        @NotBlank(message = "Versión de Privacidad requerida")
        @Size(max = 20)
        String privacyVersion
) {
    private static final Pattern PHONE_CHARS = Pattern.compile("^[0-9+()\\- .]+$");

    @AssertTrue(message = "Debes revisar y aceptar la versión vigente de los documentos legales")
    public boolean isLegalVersionCurrent() {
        return LegalDocuments.TERMS_VERSION.equals(termsVersion)
                && LegalDocuments.PRIVACY_VERSION.equals(privacyVersion);
    }

    @AssertTrue(message = "Debes indicar al menos un email o un teléfono")
    public boolean isContactProvided() {
        return hasText(email) || hasText(phone);
    }

    @AssertTrue(message = "El nombre debe tener al menos 2 caracteres")
    public boolean isTrimmedNameValid() {
        if (!hasText(name)) return true;
        String trimmed = name.trim();
        return trimmed.length() >= 2 && !hasUnsupportedControls(trimmed, false);
    }

    @AssertTrue(message = "El teléfono no parece válido")
    public boolean isPhoneValid() {
        if (!hasText(phone)) return true;
        String trimmed = phone.trim();
        if (!PHONE_CHARS.matcher(trimmed).matches()) return false;
        long digits = trimmed.chars().filter(Character::isDigit).count();
        return digits >= 6 && digits <= 20;
    }

    @AssertTrue(message = "El mensaje contiene caracteres no permitidos")
    public boolean isMessageSafe() {
        return message == null || !hasUnsupportedControls(message, true);
    }

    private static boolean hasUnsupportedControls(String value, boolean allowLineBreaks) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isISOControl(ch)) continue;
            if (allowLineBreaks && (ch == '\n' || ch == '\r' || ch == '\t')) continue;
            return true;
        }
        return false;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
