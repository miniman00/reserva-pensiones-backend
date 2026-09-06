package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.AdminAuditAction;
import uy.pensiones.enums.AdminAuditEntityType;
import uy.pensiones.enums.BackofficeRole;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.repo.BackofficeUserRepository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class BackofficeStaffService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,79}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final BackofficeUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final BackofficeSystemSuperAdminService systemAdmin;
    private final AdminAuditService audit;
    private final BackofficeMfaService mfa;
    private final BackofficeMfaPolicy mfaPolicy;

    public BackofficeStaffService(BackofficeUserRepository users,
                                  PasswordEncoder passwordEncoder,
                                  BackofficeSystemSuperAdminService systemAdmin,
                                  AdminAuditService audit,
                                  BackofficeMfaService mfa,
                                  BackofficeMfaPolicy mfaPolicy) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.systemAdmin = systemAdmin;
        this.audit = audit;
        this.mfa = mfa;
        this.mfaPolicy = mfaPolicy;
    }

    @Transactional(readOnly = true)
    public List<StaffUserDTO> list() {
        return users.findAllByOrderByDisplayNameAscUsernameAsc().stream().map(this::toDto).toList();
    }

    @Transactional
    public StaffUserDTO create(CreateStaffRequest request, BackofficeUser actor) {
        String username = normalizeUsername(request.username());
        if (systemAdmin.isSystemUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ese usuario está reservado para el SUPER_ADMIN de sistema");
        }
        if (users.existsByUsernameIgnoreCase(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un usuario interno con ese nombre");
        }
        BackofficeAuthenticationService.validatePassword(request.temporaryPassword());
        BackofficeRole role = request.role() == null ? BackofficeRole.ADMIN : request.role();
        if (role == BackofficeRole.SUPER_ADMIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El rol SUPER_ADMIN está reservado para la cuenta administrada por el servidor");
        }

        String reason = audit.requireReason(request.reason());
        BackofficeUser user = BackofficeUser.builder()
                .username(username)
                .displayName(required(request.displayName(), "El nombre es obligatorio", 160))
                .email(cleanEmail(request.email()))
                .passwordHash(passwordEncoder.encode(request.temporaryPassword()))
                .role(role)
                .active(true)
                .mustChangePassword(true)
                .build();
        BackofficeUser saved = users.save(user);
        audit.record(actor, AdminAuditAction.ADMIN_CREATE_EMPLOYEE, AdminAuditEntityType.BACKOFFICE_USER,
                saved.getId(), null, snapshot(saved), reason);
        return toDto(saved);
    }

    @Transactional
    public StaffUserDTO update(Long id, UpdateStaffRequest request, BackofficeUser current) {
        BackofficeUser user = find(id);
        if (user.isSystemManaged() || systemAdmin.isSystemUsername(user.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La cuenta SUPER_ADMIN de sistema se administra mediante configuración del servidor");
        }
        if (user.getId().equals(current.getId()) && Boolean.FALSE.equals(request.active())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No puedes desactivar tu propia cuenta");
        }
        String nextDisplayName = request.displayName() == null
                ? user.getDisplayName()
                : required(request.displayName(), "El nombre es obligatorio", 160);
        String nextEmail = request.email() == null ? user.getEmail() : cleanEmail(request.email());
        boolean profileChanged = !Objects.equals(nextDisplayName, user.getDisplayName())
                || !Objects.equals(nextEmail, user.getEmail());
        boolean roleChanged = request.role() != null && request.role() != user.getRole();
        boolean activeChanged = request.active() != null && request.active() != user.isActive();
        boolean anyChange = profileChanged || roleChanged || activeChanged;
        String reason = anyChange ? audit.requireReason(request.reason()) : null;
        Map<String, Object> before = anyChange ? snapshot(user) : null;

        user.setDisplayName(nextDisplayName);
        user.setEmail(nextEmail);
        if (request.role() == BackofficeRole.SUPER_ADMIN && user.getRole() != BackofficeRole.SUPER_ADMIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El rol SUPER_ADMIN está reservado para la cuenta administrada por el servidor");
        }
        if (request.role() != null && request.role() != user.getRole()) {
            if (user.getId().equals(current.getId()) && user.getRole() == BackofficeRole.SUPER_ADMIN) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "No puedes quitarte tu propio rol SUPER_ADMIN");
            }
            if (user.getRole() == BackofficeRole.SUPER_ADMIN
                    && request.role() != BackofficeRole.SUPER_ADMIN
                    && users.countByRoleAndActiveTrue(BackofficeRole.SUPER_ADMIN) <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Debe existir al menos un SUPER_ADMIN activo");
            }
            user.setRole(request.role());
            user.setSessionVersion(user.getSessionVersion() + 1);
        }
        if (request.active() != null && request.active() != user.isActive()) {
            if (!request.active() && user.getRole() == BackofficeRole.SUPER_ADMIN
                    && users.countByRoleAndActiveTrue(BackofficeRole.SUPER_ADMIN) <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "No puedes desactivar el último SUPER_ADMIN");
            }
            user.setActive(request.active());
            user.setSessionVersion(user.getSessionVersion() + 1);
        }
        BackofficeUser saved = users.save(user);
        if (profileChanged) {
            audit.record(current, AdminAuditAction.ADMIN_UPDATE_EMPLOYEE_PROFILE, AdminAuditEntityType.BACKOFFICE_USER,
                    saved.getId(), before, snapshot(saved), reason);
        }
        if (roleChanged) {
            audit.record(current, AdminAuditAction.ADMIN_CHANGE_EMPLOYEE_ROLE, AdminAuditEntityType.BACKOFFICE_USER,
                    saved.getId(), before, snapshot(saved), reason);
        }
        if (activeChanged) {
            AdminAuditAction action = saved.isActive()
                    ? AdminAuditAction.ADMIN_REACTIVATE_EMPLOYEE
                    : AdminAuditAction.ADMIN_DISABLE_EMPLOYEE;
            audit.record(current, action, AdminAuditEntityType.BACKOFFICE_USER,
                    saved.getId(), before, snapshot(saved), reason);
        }
        return toDto(saved);
    }

    @Transactional
    public StaffUserDTO resetPassword(Long id, ResetPasswordRequest request, BackofficeUser actor) {
        BackofficeAuthenticationService.validatePassword(request.temporaryPassword());
        BackofficeUser user = find(id);
        if (user.isSystemManaged() || systemAdmin.isSystemUsername(user.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La contraseña del SUPER_ADMIN de sistema se administra mediante configuración del servidor");
        }
        String reason = audit.requireReason(request.reason());
        Map<String, Object> before = snapshot(user);
        user.setPasswordHash(passwordEncoder.encode(request.temporaryPassword()));
        user.setMustChangePassword(true);
        user.setFailedLoginAttempts(0);
        user.setLastFailedLoginAt(null);
        user.setLockedUntil(null);
        user.setSessionVersion(user.getSessionVersion() + 1);
        BackofficeUser saved = users.save(user);
        audit.record(actor, AdminAuditAction.ADMIN_RESET_EMPLOYEE_PASSWORD, AdminAuditEntityType.BACKOFFICE_USER,
                saved.getId(), before, snapshot(saved), reason);
        return toDto(saved);
    }

    @Transactional
    public StaffUserDTO resetMfa(Long id, ResetMfaRequest request, BackofficeUser actor) {
        String reason = audit.requireReason(request.reason());
        mfa.resetForStaff(id, actor, reason);
        return toDto(find(id));
    }

    @Transactional
    public StaffUserDTO unlock(Long id, UnlockStaffRequest request, BackofficeUser actor) {
        BackofficeUser user = find(id);
        boolean hasSecurityState = user.getLockedUntil() != null || user.getFailedLoginAttempts() > 0;
        if (!hasSecurityState) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La cuenta interna no está bloqueada ni tiene intentos fallidos pendientes");
        }
        String reason = audit.requireReason(request.reason());
        Map<String, Object> before = snapshot(user);
        user.setFailedLoginAttempts(0);
        user.setLastFailedLoginAt(null);
        user.setLockedUntil(null);
        BackofficeUser saved = users.save(user);
        audit.record(actor, AdminAuditAction.ADMIN_UNLOCK_EMPLOYEE, AdminAuditEntityType.BACKOFFICE_USER,
                saved.getId(), before, snapshot(saved), reason);
        return toDto(saved);
    }

    private Map<String, Object> snapshot(BackofficeUser user) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", user.getId());
        value.put("username", user.getUsername());
        value.put("displayName", user.getDisplayName());
        value.put("email", user.getEmail());
        value.put("role", user.getRole());
        value.put("active", user.isActive());
        value.put("mustChangePassword", user.isMustChangePassword());
        value.put("failedLoginAttempts", user.getFailedLoginAttempts());
        value.put("lastFailedLoginAt", user.getLastFailedLoginAt());
        value.put("lockedUntil", user.getLockedUntil());
        value.put("sessionVersion", user.getSessionVersion());
        value.put("systemManaged", user.isSystemManaged());
        return value;
    }

    private BackofficeUser find(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario interno no encontrado"));
    }

    private StaffUserDTO toDto(BackofficeUser user) {
        return new StaffUserDTO(
                user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(), user.getRole(),
                user.isActive(), user.isMustChangePassword(), user.isSystemManaged() || systemAdmin.isSystemUsername(user.getUsername()),
                user.getFailedLoginAttempts(), user.getLockedUntil(),
                user.getMfaEnabledAt() != null, mfaPolicy.requiredFor(user), user.getMfaLockedUntil(),
                user.getLastLoginAt(), user.getCreatedAt(), user.getUpdatedAt()
        );
    }

    private String normalizeUsername(String value) {
        String username = required(value, "El usuario es obligatorio", 80).toLowerCase(Locale.ROOT);
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El usuario debe tener entre 3 y 80 caracteres y usar sólo letras minúsculas, números, punto, guion o guion bajo");
        }
        return username;
    }

    private String cleanEmail(String value) {
        String email = clean(value, 190);
        if (email != null && !EMAIL_PATTERN.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El correo interno no tiene un formato válido");
        }
        return email == null ? null : email.toLowerCase(Locale.ROOT);
    }

    private String required(String value, String message, int max) {
        String cleaned = clean(value, max);
        if (cleaned == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        return cleaned;
    }

    private String clean(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.trim();
        if (cleaned.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El valor no puede superar " + max + " caracteres");
        }
        return cleaned;
    }

    public record StaffUserDTO(
            Long id,
            String username,
            String displayName,
            String email,
            BackofficeRole role,
            boolean active,
            boolean mustChangePassword,
            boolean systemManaged,
            int failedLoginAttempts,
            OffsetDateTime lockedUntil,
            boolean mfaEnabled,
            boolean mfaRequired,
            OffsetDateTime mfaLockedUntil,
            OffsetDateTime lastLoginAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    public record CreateStaffRequest(String username, String displayName, String email,
                                     BackofficeRole role, String temporaryPassword, String reason) {}
    public record UpdateStaffRequest(String displayName, String email, BackofficeRole role, Boolean active, String reason) {}
    public record ResetPasswordRequest(String temporaryPassword, String reason) {}
    public record UnlockStaffRequest(String reason) {}
    public record ResetMfaRequest(String reason) {}
}
