package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.BackofficeMfaRecoveryCode;
import uy.pensiones.model.BackofficeSession;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.repo.BackofficeMfaRecoveryCodeRepository;
import uy.pensiones.repo.BackofficeSessionRepository;
import uy.pensiones.repo.BackofficeUserRepository;
import uy.pensiones.security.BackofficePrincipal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;

@Service
public class BackofficeMfaService {
    private static final Duration ENROLLMENT_TTL = Duration.ofMinutes(10);
    private static final Duration MFA_LOCK_DURATION = Duration.ofMinutes(15);
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int RECOVERY_CODE_COUNT = 10;
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BackofficeUserRepository users;
    private final BackofficeSessionRepository sessions;
    private final BackofficeMfaRecoveryCodeRepository recoveryCodes;
    private final BackofficeMfaPolicy policy;
    private final BackofficeMfaCrypto crypto;
    private final BackofficeReauthenticationService reauthentication;
    private final AdminAuditService audit;
    private final MailService mail;

    public BackofficeMfaService(BackofficeUserRepository users,
                                BackofficeSessionRepository sessions,
                                BackofficeMfaRecoveryCodeRepository recoveryCodes,
                                BackofficeMfaPolicy policy,
                                BackofficeMfaCrypto crypto,
                                BackofficeReauthenticationService reauthentication,
                                AdminAuditService audit,
                                MailService mail) {
        this.users = users;
        this.sessions = sessions;
        this.recoveryCodes = recoveryCodes;
        this.policy = policy;
        this.crypto = crypto;
        this.reauthentication = reauthentication;
        this.audit = audit;
        this.mail = mail;
    }

    @Transactional(readOnly = true)
    public MfaStatus status(BackofficePrincipal principal) {
        BackofficeSession session = session(principal);
        BackofficeUser user = session.getBackofficeUser();
        boolean enrolled = policy.enrolled(user);
        boolean required = policy.requiredFor(user);
        boolean verified = policy.satisfied(user, session);
        return new MfaStatus(policy.enabled(), required, enrolled, verified,
                required && !enrolled,
                policy.enabled() && enrolled && !verified,
                recoveryCodes.countByBackofficeUser_IdAndUsedAtIsNull(user.getId()),
                user.getMfaEnabledAt(), user.getMfaLastVerifiedAt(), user.getMfaLockedUntil());
    }

    @Transactional
    public Enrollment beginEnrollment(BackofficePrincipal principal) {
        ensureFeature();
        reauthentication.requireRecent(principal);
        BackofficeUser user = lockedUser(principal);
        if (policy.enrolled(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La autenticación MFA ya está configurada");
        }
        String secret = BackofficeTotp.newSecret();
        OffsetDateTime expiresAt = now().plus(ENROLLMENT_TTL);
        user.setMfaPendingSecretCiphertext(crypto.encrypt(secret));
        user.setMfaPendingExpiresAt(expiresAt);
        users.save(user);
        String label = policy.issuer() + ":" + user.getUsername();
        String uri = "otpauth://totp/" + UriUtils.encodePathSegment(label, StandardCharsets.UTF_8)
                + "?secret=" + secret
                + "&issuer=" + UriUtils.encodeQueryParam(policy.issuer(), StandardCharsets.UTF_8)
                + "&algorithm=SHA1&digits=6&period=30";
        return new Enrollment(secret, uri, policy.issuer(), user.getUsername(), expiresAt);
    }

    @Transactional
    public EnrollmentResult confirmEnrollment(BackofficePrincipal principal, String rawCode) {
        ensureFeature();
        BackofficeSession session = sessionForUpdate(principal);
        BackofficeUser user = lockedUser(principal);
        OffsetDateTime now = now();
        checkMfaLock(user, now);
        if (policy.enrolled(user)) throw new ResponseStatusException(HttpStatus.CONFLICT, "La autenticación MFA ya está configurada");
        if (user.getMfaPendingSecretCiphertext() == null || user.getMfaPendingExpiresAt() == null
                || !user.getMfaPendingExpiresAt().isAfter(now)) {
            clearPending(user);
            users.save(user);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La configuración MFA venció. Iníciala nuevamente.");
        }
        String secret = crypto.decrypt(user.getMfaPendingSecretCiphertext());
        OptionalLong counter = BackofficeTotp.verify(secret, rawCode, now.toInstant(), null);
        if (counter.isEmpty()) {
            registerFailure(user, now);
            throw invalidCode();
        }

        user.setMfaSecretCiphertext(crypto.encrypt(secret));
        user.setMfaEnabledAt(now);
        user.setMfaLastVerifiedAt(now);
        user.setMfaLastAcceptedCounter(counter.getAsLong());
        user.setMfaFailedAttempts(0);
        user.setMfaLockedUntil(null);
        clearPending(user);
        users.save(user);

        session.setMfaVerifiedAt(now);
        session.setLastReauthenticatedAt(now);
        sessions.save(session);

        List<String> rawRecoveryCodes = replaceRecoveryCodes(user, now);
        audit.record(user, AdminAuditAction.ADMIN_ENABLE_OWN_MFA, AdminAuditEntityType.BACKOFFICE_USER,
                user.getId(), null, mfaAuditSnapshot(user), "MFA TOTP habilitado por el titular");
        notifyMfaChange(user, "Se habilitó la autenticación en dos pasos (MFA) para tu cuenta del Backoffice.");
        return new EnrollmentResult(rawRecoveryCodes, statusFrom(user, session));
    }

    @Transactional
    public MfaStatus verify(BackofficePrincipal principal, String rawCode) {
        ensureFeature();
        BackofficeSession session = sessionForUpdate(principal);
        BackofficeUser user = lockedUser(principal);
        if (!policy.enrolled(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Primero debes configurar MFA");
        }
        OffsetDateTime now = now();
        checkMfaLock(user, now);
        Verification verification = verifyFactor(user, rawCode, now);
        if (!verification.accepted()) {
            registerFailure(user, now);
            throw invalidCode();
        }
        applySuccessfulVerification(user, session, verification, now);
        if (verification.recoveryCodeUsed()) {
            audit.record(user, AdminAuditAction.ADMIN_USE_MFA_RECOVERY_CODE, AdminAuditEntityType.BACKOFFICE_USER,
                    user.getId(), null, mfaAuditSnapshot(user), "Acceso MFA mediante código de recuperación");
            notifyMfaChange(user, "Se utilizó un código de recuperación MFA para acceder a tu cuenta del Backoffice.");
        }
        return statusFrom(user, session);
    }

    @Transactional
    public RecoveryCodesResult regenerateRecoveryCodes(BackofficePrincipal principal, String rawCode) {
        ensureFeature();
        reauthentication.requireRecent(principal);
        BackofficeSession session = sessionForUpdate(principal);
        BackofficeUser user = lockedUser(principal);
        if (!policy.enrolled(user)) throw new ResponseStatusException(HttpStatus.CONFLICT, "MFA no está configurado");
        OffsetDateTime now = now();
        checkMfaLock(user, now);
        Verification verification = verifyFactor(user, rawCode, now);
        if (!verification.accepted()) {
            registerFailure(user, now);
            throw invalidCode();
        }
        applySuccessfulVerification(user, session, verification, now);
        List<String> codes = replaceRecoveryCodes(user, now);
        audit.record(user, AdminAuditAction.ADMIN_REGENERATE_MFA_RECOVERY_CODES, AdminAuditEntityType.BACKOFFICE_USER,
                user.getId(), null, mfaAuditSnapshot(user), "Códigos de recuperación MFA regenerados por el titular");
        notifyMfaChange(user, "Se regeneraron los códigos de recuperación MFA de tu cuenta del Backoffice.");
        return new RecoveryCodesResult(codes, codes.size());
    }

    @Transactional
    public void resetForStaff(Long userId, BackofficeUser actor, String reason) {
        ensureFeature();
        BackofficeUser user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario interno no encontrado"));
        if (user.getId().equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No puedes resetear tu propio MFA desde Equipo interno. Usa tus códigos de recuperación.");
        }
        if (!policy.enrolled(user) && user.getMfaPendingSecretCiphertext() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La cuenta no tiene MFA configurado ni un enrolamiento pendiente");
        }
        var before = mfaAuditSnapshot(user);
        clearMfa(user);
        user.setSessionVersion(user.getSessionVersion() + 1);
        users.save(user);
        recoveryCodes.deleteByBackofficeUser_Id(user.getId());
        audit.record(actor, AdminAuditAction.ADMIN_RESET_EMPLOYEE_MFA, AdminAuditEntityType.BACKOFFICE_USER,
                user.getId(), before, mfaAuditSnapshot(user), reason);
        notifyMfaChange(user, "Un SUPER_ADMIN reseteó el MFA de tu cuenta del Backoffice. Deberás configurarlo nuevamente en el próximo acceso.");
    }

    private Verification verifyFactor(BackofficeUser user, String rawCode, OffsetDateTime now) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.matches("\\d{6}")) {
            String secret = crypto.decrypt(user.getMfaSecretCiphertext());
            OptionalLong counter = BackofficeTotp.verify(secret, code, now.toInstant(), user.getMfaLastAcceptedCounter());
            if (counter.isPresent()) return new Verification(true, false, counter.getAsLong(), null);
        }
        String recoveryHash = sha256(normalizeRecoveryCode(code));
        if (recoveryHash != null) {
            BackofficeMfaRecoveryCode recovery = recoveryCodes
                    .findByBackofficeUser_IdAndCodeHashAndUsedAtIsNull(user.getId(), recoveryHash).orElse(null);
            if (recovery != null) return new Verification(true, true, null, recovery);
        }
        return new Verification(false, false, null, null);
    }

    private void applySuccessfulVerification(BackofficeUser user, BackofficeSession session,
                                             Verification verification, OffsetDateTime now) {
        if (verification.counter() != null) user.setMfaLastAcceptedCounter(verification.counter());
        if (verification.recovery() != null) {
            verification.recovery().setUsedAt(now);
            recoveryCodes.save(verification.recovery());
        }
        user.setMfaLastVerifiedAt(now);
        user.setMfaFailedAttempts(0);
        user.setMfaLockedUntil(null);
        users.save(user);
        session.setMfaVerifiedAt(now);
        sessions.save(session);
    }

    private void registerFailure(BackofficeUser user, OffsetDateTime now) {
        int attempts = user.getMfaFailedAttempts() + 1;
        user.setMfaFailedAttempts(attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            user.setMfaFailedAttempts(0);
            user.setMfaLockedUntil(now.plus(MFA_LOCK_DURATION));
        }
        users.save(user);
    }

    private void checkMfaLock(BackofficeUser user, OffsetDateTime now) {
        if (user.getMfaLockedUntil() != null && user.getMfaLockedUntil().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiados intentos MFA. Inténtalo nuevamente más tarde o usa un código de recuperación cuando termine el bloqueo.");
        }
        if (user.getMfaLockedUntil() != null) {
            user.setMfaLockedUntil(null);
            user.setMfaFailedAttempts(0);
        }
    }

    private List<String> replaceRecoveryCodes(BackofficeUser user, OffsetDateTime now) {
        recoveryCodes.deleteByBackofficeUser_Id(user.getId());
        List<String> raw = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = newRecoveryCode();
            raw.add(code);
            recoveryCodes.save(BackofficeMfaRecoveryCode.builder()
                    .backofficeUser(user).codeHash(sha256(normalizeRecoveryCode(code))).createdAt(now).build());
        }
        return List.copyOf(raw);
    }

    private String newRecoveryCode() {
        StringBuilder raw = new StringBuilder(12);
        for (int i = 0; i < 12; i++) raw.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
        return raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12);
    }

    private String normalizeRecoveryCode(String value) {
        if (value == null) return null;
        String normalized = value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return normalized.matches("[A-HJ-NP-Z2-9]{12}") ? normalized : null;
    }

    private String sha256(String value) {
        if (value == null) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no está disponible", ex);
        }
    }

    private BackofficeSession session(BackofficePrincipal principal) {
        if (principal == null || principal.sessionId() == null) throw unauthorized();
        BackofficeSession session = sessions.findById(principal.sessionId()).orElseThrow(this::unauthorized);
        BackofficeUser user = session.getBackofficeUser();
        if (user == null || !user.getId().equals(principal.id()) || !user.isActive()
                || session.getSessionVersion() != principal.sessionVersion()
                || user.getSessionVersion() != principal.sessionVersion()) throw unauthorized();
        return session;
    }

    private BackofficeSession sessionForUpdate(BackofficePrincipal principal) {
        return session(principal);
    }

    private BackofficeUser lockedUser(BackofficePrincipal principal) {
        if (principal == null || principal.id() == null) throw unauthorized();
        BackofficeUser user = users.findByIdForUpdate(principal.id()).orElseThrow(this::unauthorized);
        if (!user.isActive() || user.getSessionVersion() != principal.sessionVersion()) throw unauthorized();
        return user;
    }

    private MfaStatus statusFrom(BackofficeUser user, BackofficeSession session) {
        boolean enrolled = policy.enrolled(user);
        boolean required = policy.requiredFor(user);
        boolean verified = policy.satisfied(user, session);
        return new MfaStatus(policy.enabled(), required, enrolled, verified,
                required && !enrolled, policy.enabled() && enrolled && !verified,
                recoveryCodes.countByBackofficeUser_IdAndUsedAtIsNull(user.getId()),
                user.getMfaEnabledAt(), user.getMfaLastVerifiedAt(), user.getMfaLockedUntil());
    }

    private java.util.Map<String, Object> mfaAuditSnapshot(BackofficeUser user) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("mfaEnabled", policy.enrolled(user));
        data.put("mfaEnabledAt", user.getMfaEnabledAt());
        data.put("mfaLastVerifiedAt", user.getMfaLastVerifiedAt());
        data.put("recoveryCodesAvailable", recoveryCodes.countByBackofficeUser_IdAndUsedAtIsNull(user.getId()));
        return data;
    }

    private void clearPending(BackofficeUser user) {
        user.setMfaPendingSecretCiphertext(null);
        user.setMfaPendingExpiresAt(null);
    }

    private void clearMfa(BackofficeUser user) {
        user.setMfaSecretCiphertext(null);
        clearPending(user);
        user.setMfaEnabledAt(null);
        user.setMfaLastVerifiedAt(null);
        user.setMfaLastAcceptedCounter(null);
        user.setMfaFailedAttempts(0);
        user.setMfaLockedUntil(null);
    }

    private void notifyMfaChange(BackofficeUser user, String action) {
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            mail.sendBackofficeSecurityNotice(user.getEmail(), user.getDisplayName(), action);
        }
    }

    private void ensureFeature() {
        if (!policy.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MFA no está habilitado en este entorno");
    }

    private ResponseStatusException invalidCode() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "El código MFA no es válido, ya fue utilizado o está fuera de la ventana permitida");
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "La sesión de Backoffice ya no es válida");
    }

    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }

    private record Verification(boolean accepted, boolean recoveryCodeUsed, Long counter, BackofficeMfaRecoveryCode recovery) {}

    public record MfaStatus(boolean featureEnabled, boolean required, boolean enabled, boolean verified,
                            boolean enrollmentRequired, boolean verificationRequired, long recoveryCodesAvailable,
                            OffsetDateTime enabledAt, OffsetDateTime lastVerifiedAt, OffsetDateTime lockedUntil) {}
    public record Enrollment(String secret, String otpauthUri, String issuer, String account, OffsetDateTime expiresAt) {}
    public record EnrollmentResult(List<String> recoveryCodes, MfaStatus status) {}
    public record RecoveryCodesResult(List<String> recoveryCodes, int count) {}
}
