package uy.pensiones.pension;

import org.springframework.data.jpa.domain.Specification;
import uy.pensiones.model.Pension;

import java.util.ArrayList;
import java.util.List;

/**
 * Filtros complementarios del marketplace que no forman parte de la firma histórica de PensionSpecs.
 * Mantenerlos separados evita volver a romper los consumidores existentes del buscador.
 */
public final class PensionSearchExtensions {

    private static final int MAX_TEXT_TERMS = 10;
    private static final int MAX_TEXT_TERM_LENGTH = 80;
    private static final int MAX_AREA_POINTS = 50;

    private PensionSearchExtensions() {
    }

    /**
     * Cada término adicional debe aparecer en algún campo buscable de la misma pensión.
     * Ej.: "Montevideo" + "La Blanqueada" produce una intersección natural de criterios.
     */
    public static Specification<Pension> freeTextTerms(List<String> rawTerms) {
        List<String> terms = normalizeTerms(rawTerms);
        return (root, query, cb) -> {
            if (terms.isEmpty()) {
                return cb.conjunction();
            }

            var result = cb.conjunction();
            var searchText = PensionSpecs.publicSearchText(root, cb);
            var state = cb.lower(root.<String>get("state"));
            for (String term : terms) {
                String like = PensionSpecs.containsPattern(term);
                result = cb.and(result, cb.or(
                        cb.like(searchText, like, '\\'),
                        cb.like(state, like, '\\')
                ));
            }
            return result;
        };
    }

    /**
     * Filtra por el polígono dibujado en el mapa. Los puntos llegan como "lat,lng".
     * Se aplica primero el bounding box y luego la comprobación exacta mediante la
     * función PostgreSQL pension_point_in_polygon creada por Flyway.
     */
    public static Specification<Pension> insidePolygon(List<String> rawAreaPoints) {
        ParsedPolygon polygon = parsePolygon(rawAreaPoints);
        return (root, query, cb) -> {
            if (!polygon.requested()) {
                return cb.conjunction();
            }
            if (!polygon.valid()) {
                return cb.disjunction();
            }

            var lat = root.<Double>get("lat");
            var lng = root.<Double>get("lng");
            var bounds = cb.and(
                    cb.isNotNull(lat),
                    cb.isNotNull(lng),
                    cb.greaterThanOrEqualTo(lat, polygon.minLat()),
                    cb.lessThanOrEqualTo(lat, polygon.maxLat()),
                    cb.greaterThanOrEqualTo(lng, polygon.minLng()),
                    cb.lessThanOrEqualTo(lng, polygon.maxLng())
            );
            var inside = cb.function(
                    "pension_point_in_polygon",
                    Boolean.class,
                    lat,
                    lng,
                    cb.literal(polygon.postgresPolygon())
            );
            return cb.and(bounds, cb.isTrue(inside));
        };
    }

    static List<String> normalizeTerms(List<String> rawTerms) {
        if (rawTerms == null || rawTerms.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String raw : rawTerms) {
            if (result.size() >= MAX_TEXT_TERMS) break;
            if (raw == null) continue;
            String term = raw.trim().replaceAll("\\s+", " ");
            if (term.isEmpty()) continue;
            if (term.length() > MAX_TEXT_TERM_LENGTH) {
                term = term.substring(0, MAX_TEXT_TERM_LENGTH);
            }
            String candidate = term;
            boolean duplicate = result.stream().anyMatch(value -> value.equalsIgnoreCase(candidate));
            if (!duplicate) result.add(term);
        }
        return List.copyOf(result);
    }

    static ParsedPolygon parsePolygon(List<String> rawAreaPoints) {
        if (rawAreaPoints == null || rawAreaPoints.isEmpty()) {
            return ParsedPolygon.notRequested();
        }
        if (rawAreaPoints.size() < 3 || rawAreaPoints.size() > MAX_AREA_POINTS) {
            return ParsedPolygon.invalid();
        }

        List<AreaPoint> points = new ArrayList<>();
        for (String raw : rawAreaPoints) {
            AreaPoint point = parsePoint(raw);
            if (point == null) return ParsedPolygon.invalid();
            points.add(point);
        }

        double minLat = points.stream().mapToDouble(AreaPoint::lat).min().orElseThrow();
        double maxLat = points.stream().mapToDouble(AreaPoint::lat).max().orElseThrow();
        double minLng = points.stream().mapToDouble(AreaPoint::lng).min().orElseThrow();
        double maxLng = points.stream().mapToDouble(AreaPoint::lng).max().orElseThrow();

        String postgresPolygon = points.stream()
                .map(point -> "(" + Double.toString(point.lng()) + "," + Double.toString(point.lat()) + ")")
                .reduce((left, right) -> left + "," + right)
                .map(value -> "(" + value + ")")
                .orElse("");

        return new ParsedPolygon(true, true, minLat, maxLat, minLng, maxLng, postgresPolygon);
    }

    private static AreaPoint parsePoint(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(",", -1);
        if (parts.length != 2) return null;
        try {
            double lat = Double.parseDouble(parts[0].trim());
            double lng = Double.parseDouble(parts[1].trim());
            if (!Double.isFinite(lat) || !Double.isFinite(lng)) return null;
            if (lat < -90d || lat > 90d || lng < -180d || lng > 180d) return null;
            return new AreaPoint(lat, lng);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    record AreaPoint(double lat, double lng) {
    }

    record ParsedPolygon(
            boolean requested,
            boolean valid,
            double minLat,
            double maxLat,
            double minLng,
            double maxLng,
            String postgresPolygon
    ) {
        static ParsedPolygon notRequested() {
            return new ParsedPolygon(false, true, 0, 0, 0, 0, "");
        }

        static ParsedPolygon invalid() {
            return new ParsedPolygon(true, false, 0, 0, 0, 0, "");
        }
    }
}
