package uy.pensiones.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StudyCenterCatalogServiceTest {

    @Test
    void normalizesNamesForStableMatching() {
        assertEquals("facultad de ingenieria",
                StudyCenterCatalogService.normalizeKey("  Facultad   de Ingeniería "));
        assertEquals("Facultad de Ingeniería",
                StudyCenterCatalogService.normalizeDisplayName("  Facultad   de Ingeniería "));
    }

    @Test
    void blankNamesBecomeNull() {
        assertNull(StudyCenterCatalogService.normalizeKey("   "));
        assertNull(StudyCenterCatalogService.normalizeDisplayName(null));
    }
}
