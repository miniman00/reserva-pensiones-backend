package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class BackofficeSystemSuperAdminServiceTest {

    @Test
    void validatesConfiguredBcryptAndMatchesOnlyCorrectPassword() {
        var encoder = new BCryptPasswordEncoder(12);
        String hash = encoder.encode("ClaveSegura123");
        var service = new BackofficeSystemSuperAdminService(
                encoder, "Soporte", hash, "Soporte interno", "SOPORTE@EXAMPLE.COM", false
        );

        assertTrue(service.isConfigured());
        assertTrue(service.isSystemUsername(" soporte "));
        assertTrue(service.matches("ClaveSegura123"));
        assertFalse(service.matches("otra-clave"));
        assertEquals("soporte@example.com", service.email());
    }

    @Test
    void emptyHashLeavesSystemAccountDisabled() {
        var service = new BackofficeSystemSuperAdminService(
                new BCryptPasswordEncoder(12), "soporte", "", "Soporte", "", false
        );
        assertFalse(service.isConfigured());
        assertFalse(service.matches("cualquier-clave"));
    }

    @Test
    void rejectsPlainTextPasswordInHashProperty() {
        assertThrows(IllegalStateException.class, () -> new BackofficeSystemSuperAdminService(
                new BCryptPasswordEncoder(12), "soporte", "ClaveEnTextoPlano123", "Soporte", null, false
        ));
    }
}
