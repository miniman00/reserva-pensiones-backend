package uy.pensiones.service;

final class PublicContactNumbers {

    private PublicContactNumbers() {}

    static String normalizeWhatsapp(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if (!trimmed.matches("^[0-9+() .\\-]{7,30}$")) return null;

        String compact = trimmed.replaceAll("[\\s().\\-]", "");
        String digits;
        if (compact.startsWith("+")) {
            digits = compact.substring(1);
        } else if (compact.startsWith("00")) {
            digits = compact.substring(2);
        } else {
            return null;
        }

        if (!digits.matches("^[1-9][0-9]{6,14}$")) return null;
        return "+" + digits;
    }
}
