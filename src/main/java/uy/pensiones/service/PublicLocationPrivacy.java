package uy.pensiones.service;

/**
 * Reduce la precisión de coordenadas en superficies de enumeración masiva
 * (listados, mapa y favoritos). La ficha individual conserva la ubicación
 * exacta porque el visitante ya eligió explícitamente un anuncio concreto.
 */
public final class PublicLocationPrivacy {

    private static final double BULK_COORDINATE_FACTOR = 1_000d;

    private PublicLocationPrivacy() {
    }

    public static Double approximateCoordinate(Double value) {
        if (value == null || !Double.isFinite(value)) return null;
        return Math.round(value * BULK_COORDINATE_FACTOR) / BULK_COORDINATE_FACTOR;
    }
}
