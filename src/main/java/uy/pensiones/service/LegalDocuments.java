package uy.pensiones.service;

import uy.pensiones.model.User;

import java.time.LocalDate;

public final class LegalDocuments {

    public static final String TERMS_VERSION = "1.0";
    public static final String PRIVACY_VERSION = "1.0";
    public static final LocalDate EFFECTIVE_DATE = LocalDate.of(2026, 8, 24);

    private LegalDocuments() {
    }

    public static boolean isCurrent(User user) {
        return user != null
                && TERMS_VERSION.equals(user.getTermsAcceptedVersion())
                && PRIVACY_VERSION.equals(user.getPrivacyAcceptedVersion())
                && user.getLegalAcceptedAt() != null;
    }
}
