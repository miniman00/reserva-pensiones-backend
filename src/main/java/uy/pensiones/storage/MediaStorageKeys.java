package uy.pensiones.storage;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class MediaStorageKeys {
    private MediaStorageKeys() {}

    public static String original(Long pensionId, String filename) {
        requirePensionId(pensionId);
        return "pensions/" + pensionId + "/" + safeFilename(filename);
    }

    public static String pensionPrefix(Long pensionId) {
        requirePensionId(pensionId);
        return "pensions/" + pensionId + "/";
    }

    public static String allPensionsPrefix() {
        return "pensions/";
    }

    public static String variant(Long pensionId, String filename, String variant) {
        requirePensionId(pensionId);
        String safeVariant = safeSegment(variant, "variante");
        return pensionPrefix(pensionId) + ".variants/" + safeVariant + "/" + safeFilename(filename) + ".jpg";
    }

    public static String safeFilename(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Nombre de archivo vacío");
        Path name = Paths.get(value).getFileName();
        if (name == null || !name.toString().equals(value) || value.contains("..") || value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException("Nombre de archivo inválido");
        }
        return value;
    }

    public static String normalizeKey(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Clave de media vacía");
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("//") || normalized.contains("../")
                || normalized.equals("..") || normalized.endsWith("/..")) {
            throw new IllegalArgumentException("Clave de media inválida");
        }
        for (String segment : normalized.split("/")) safeSegment(segment, "clave");
        return normalized;
    }

    private static String safeSegment(String value, String label) {
        if (value == null || value.isBlank() || value.equals(".") || value.equals("..")
                || value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException("Segmento de " + label + " inválido");
        }
        return value;
    }

    private static void requirePensionId(Long pensionId) {
        if (pensionId == null || pensionId <= 0) throw new IllegalArgumentException("pensionId inválido");
    }
}
