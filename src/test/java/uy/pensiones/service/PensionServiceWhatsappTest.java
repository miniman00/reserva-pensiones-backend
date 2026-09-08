package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.model.Pension;
import uy.pensiones.web.PensionController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PensionServiceWhatsappTest {

    private final PensionService service = new PensionService(null, null, null, null, null, null, null, null, null, null, null);

    @Test
    void normalizesInternationalWhatsappWithPlusOrDoubleZeroPrefix() {
        assertEquals("+59899123456", PublicContactNumbers.normalizeWhatsapp("+598 99 123 456"));
        assertEquals("+59899123456", PublicContactNumbers.normalizeWhatsapp("00598 99 123 456"));
    }

    @Test
    void rejectsLocalOrOversizedWhatsappAsPublicContact() {
        assertNull(PublicContactNumbers.normalizeWhatsapp("099 123 456"));
        assertNull(PublicContactNumbers.normalizeWhatsapp("+1234567890123456"));
    }

    @Test
    void canonicalizesWhatsappWhenPublicButtonIsEnabled() {
        Pension pension = new Pension();
        service.applyPublicContact(pension, contactDto("+598 99 123 456", true), null);
        assertEquals("+59899123456", pension.getContactWhatsapp());
    }

    @Test
    void refusesToEnablePublicWhatsappWithLocalNumber() {
        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.applyPublicContact(new Pension(), contactDto("099 123 456", true), null)
        );
        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void allowsKeepingLocalWhatsappWhilePublicButtonRemainsDisabled() {
        Pension pension = new Pension();
        service.applyPublicContact(pension, contactDto("099 123 456", false), null);
        assertEquals("099 123 456", pension.getContactWhatsapp());
    }

    private PensionController.PensionDTO contactDto(String whatsapp, boolean showWhatsapp) {
        return new PensionController.PensionDTO(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, whatsapp, false, showWhatsapp,
                null, null, null, null, null, null, null, null, null, null,
                null, null
        );
    }
}
