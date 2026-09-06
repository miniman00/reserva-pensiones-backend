package uy.pensiones.pension;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PensionSearchExtensionsTest {

    @Test
    void normalizesAndDeduplicatesSearchTerms() {
        var result = PensionSearchExtensions.normalizeTerms(List.of(
                " Montevideo ",
                "La   Blanqueada",
                "montevideo"
        ));

        assertEquals(List.of("Montevideo", "La Blanqueada"), result);
    }

    @Test
    void buildsPostgresPolygonWithLongitudeFirst() {
        var polygon = PensionSearchExtensions.parsePolygon(List.of(
                "-34.9000,-56.1700",
                "-34.8900,-56.1500",
                "-34.9100,-56.1400"
        ));

        assertTrue(polygon.requested());
        assertTrue(polygon.valid());
        assertEquals(-34.91, polygon.minLat(), 0.000001);
        assertEquals(-34.89, polygon.maxLat(), 0.000001);
        assertEquals("((-56.17,-34.9),(-56.15,-34.89),(-56.14,-34.91))", polygon.postgresPolygon());
    }

    @Test
    void rejectsIncompletePolygon() {
        var polygon = PensionSearchExtensions.parsePolygon(List.of(
                "-34.9000,-56.1700",
                "-34.8900,-56.1500"
        ));

        assertTrue(polygon.requested());
        assertFalse(polygon.valid());
    }
    @Test
    void escapesSqlLikeWildcardsInLiteralSearchText() {
        assertEquals("%50\\%\\_\\\\%", PensionSpecs.containsPattern("50%_\\"));
    }

}
