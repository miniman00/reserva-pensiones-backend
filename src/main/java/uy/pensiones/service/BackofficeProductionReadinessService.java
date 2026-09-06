package uy.pensiones.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.enums.BackofficeRole;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.repo.BackofficeUserRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class BackofficeProductionReadinessService {
    private final BackofficeUserRepository users;
    private final BackofficeSystemSuperAdminService systemAdmin;
    private final Environment environment;
    private final boolean mfaEnabled;
    private final boolean breakGlassEnabled;
    private final boolean cookieSecure;
    private final String cookieSameSite;
    private final Duration sessionTtl;
    private final Duration idleTimeout;
    private final boolean mailEnabled;

    public BackofficeProductionReadinessService(
            BackofficeUserRepository users,
            BackofficeSystemSuperAdminService systemAdmin,
            Environment environment,
            @Value("${app.backoffice.mfa.enabled:false}") boolean mfaEnabled,
            @Value("${app.backoffice.mfa.break-glass.enabled:false}") boolean breakGlassEnabled,
            @Value("${app.backoffice.session.cookie-secure:false}") boolean cookieSecure,
            @Value("${app.backoffice.session.cookie-same-site:Strict}") String cookieSameSite,
            @Value("${app.backoffice.session.ttl:PT8H}") Duration sessionTtl,
            @Value("${app.backoffice.session.idle-timeout:PT30M}") Duration idleTimeout,
            @Value("${app.mail.enabled:false}") boolean mailEnabled
    ) {
        this.users = users;
        this.systemAdmin = systemAdmin;
        this.environment = environment;
        this.mfaEnabled = mfaEnabled;
        this.breakGlassEnabled = breakGlassEnabled;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite == null ? "" : cookieSameSite.trim();
        this.sessionTtl = sessionTtl;
        this.idleTimeout = idleTimeout;
        this.mailEnabled = mailEnabled;
    }

    @Transactional(readOnly = true)
    public Readiness evaluate() {
        List<Check> checks = new ArrayList<>();
        boolean prod = Arrays.stream(environment.getActiveProfiles()).anyMatch("prod"::equalsIgnoreCase);
        checks.add(check("PROD_PROFILE", prod, Severity.BLOCKER,
                "Perfil de producción", prod ? "El perfil prod está activo." : "SPRING_PROFILES_ACTIVE no incluye prod."));
        checks.add(check("SYSTEM_SUPER_ADMIN", systemAdmin.isConfigured(), Severity.BLOCKER,
                "SUPER_ADMIN de recuperación", systemAdmin.isConfigured() ? "La credencial administrada por infraestructura está configurada." : "Falta APP_BACKOFFICE_SUPER_ADMIN_PASSWORD_HASH."));
        checks.add(check("MFA_ENABLED", mfaEnabled, Severity.BLOCKER,
                "MFA administrativo", mfaEnabled ? "MFA está habilitado." : "MFA está deshabilitado."));
        checks.add(check("BREAK_GLASS_DISABLED", !breakGlassEnabled, Severity.BLOCKER,
                "Break-glass cerrado", !breakGlassEnabled ? "El mecanismo extraordinario está deshabilitado." : "Break-glass sigue habilitado: ciérralo antes de declarar producción lista."));
        checks.add(check("SECURE_COOKIE", cookieSecure, Severity.BLOCKER,
                "Cookie administrativa Secure", cookieSecure ? "La cookie solo viaja por HTTPS." : "app.backoffice.session.cookie-secure=false."));
        boolean strictSameSite = "Strict".equalsIgnoreCase(cookieSameSite) || "Lax".equalsIgnoreCase(cookieSameSite);
        checks.add(check("SAFE_SAMESITE", strictSameSite, Severity.BLOCKER,
                "SameSite administrativo", strictSameSite ? "SameSite=" + cookieSameSite + "." : "SameSite=None no es recomendable para el Backoffice."));
        boolean sessionBounds = sessionTtl != null && !sessionTtl.isNegative() && sessionTtl.compareTo(Duration.ofHours(12)) <= 0
                && idleTimeout != null && idleTimeout.compareTo(Duration.ofHours(1)) <= 0;
        checks.add(check("SESSION_LIMITS", sessionBounds, Severity.BLOCKER,
                "Límites de sesión", sessionBounds ? "TTL=" + sessionTtl + ", inactividad=" + idleTimeout + "." : "Revisa TTL absoluto e inactividad; para producción se espera <=12h y <=1h."));
        checks.add(check("SECURITY_MAIL", mailEnabled, Severity.WARNING,
                "Avisos de seguridad por correo", mailEnabled ? "El transporte de correo está habilitado." : "Los avisos de seguridad por correo están deshabilitados."));

        List<BackofficeUser> privileged = users.findAllByOrderByDisplayNameAscUsernameAsc().stream()
                .filter(BackofficeUser::isActive)
                .filter(u -> u.getRole() == BackofficeRole.ADMIN || u.getRole() == BackofficeRole.SUPER_ADMIN)
                .toList();
        List<String> withoutMfa = privileged.stream()
                .filter(u -> u.getMfaEnabledAt() == null || u.getMfaSecretCiphertext() == null || u.getMfaSecretCiphertext().isBlank())
                .map(BackofficeUser::getUsername).toList();
        checks.add(check("ALL_PRIVILEGED_MFA", withoutMfa.isEmpty(), Severity.BLOCKER,
                "MFA de cuentas privilegiadas", withoutMfa.isEmpty() ? "Todas las cuentas ADMIN/SUPER_ADMIN activas tienen MFA enrolado." : "Sin MFA: " + String.join(", ", withoutMfa)));
        List<String> tempPasswords = privileged.stream().filter(BackofficeUser::isMustChangePassword).map(BackofficeUser::getUsername).toList();
        checks.add(check("NO_TEMP_PASSWORDS", tempPasswords.isEmpty(), Severity.BLOCKER,
                "Contraseñas temporales", tempPasswords.isEmpty() ? "No hay cuentas privilegiadas pendientes de cambiar contraseña." : "Pendientes: " + String.join(", ", tempPasswords)));
        List<String> locked = privileged.stream().filter(u -> u.getLockedUntil() != null || u.getMfaLockedUntil() != null || u.getMfaBreakGlassLockedUntil() != null)
                .map(BackofficeUser::getUsername).toList();
        checks.add(check("NO_PRIVILEGED_LOCKS", locked.isEmpty(), Severity.WARNING,
                "Bloqueos administrativos", locked.isEmpty() ? "No hay cuentas privilegiadas con bloqueos registrados." : "Revisar bloqueos: " + String.join(", ", locked)));

        long blockers = checks.stream().filter(c -> !c.passed() && c.severity() == Severity.BLOCKER).count();
        long warnings = checks.stream().filter(c -> !c.passed() && c.severity() == Severity.WARNING).count();
        State state = blockers > 0 ? State.BLOCKED : warnings > 0 ? State.READY_WITH_WARNINGS : State.READY;
        return new Readiness(state, blockers, warnings, List.copyOf(checks));
    }

    private Check check(String code, boolean passed, Severity severity, String title, String detail) {
        return new Check(code, passed, severity, title, detail);
    }

    public enum State { READY, READY_WITH_WARNINGS, BLOCKED }
    public enum Severity { BLOCKER, WARNING }
    public record Check(String code, boolean passed, Severity severity, String title, String detail) {}
    public record Readiness(State state, long blockers, long warnings, List<Check> checks) {}
}
