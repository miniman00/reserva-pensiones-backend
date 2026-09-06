package uy.pensiones.service;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.BackofficeRole;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.repo.BackofficeUserRepository;
import uy.pensiones.security.BackofficePrincipal;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BackofficeAuthenticationServiceTest {

    @Mock BackofficeUserRepository users;
    @Mock BackofficeSystemSuperAdminService systemAdmin;
    @Mock BackofficeSessionService sessions;
    @Mock AdminAuditService audit;
    @Mock HttpServletResponse response;
    private PasswordEncoder encoder;
    private BackofficeAuthenticationService service;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder(4);
        service = new BackofficeAuthenticationService(users, encoder, systemAdmin, sessions, audit);
    }

    @Test
    void disabledAndLockedAccountsUseSameGenericAuthenticationFailure() {
        BackofficeUser disabled = user("disabled", false, "ClaveSegura123");
        when(users.findByUsernameIgnoreCase("disabled")).thenReturn(Optional.of(disabled));
        String disabledReason = reasonOf(() -> service.login("disabled", "ClaveSegura123", response));

        BackofficeUser locked = user("locked", true, "ClaveSegura123");
        locked.setLockedUntil(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        when(users.findByUsernameIgnoreCase("locked")).thenReturn(Optional.of(locked));
        String lockedReason = reasonOf(() -> service.login("locked", "ClaveSegura123", response));

        assertThat(disabledReason).isEqualTo(lockedReason).contains("No se pudo iniciar sesión");
    }

    @Test
    void staleFailuresDoNotAccumulateForever() {
        BackofficeUser user = user("admin", true, "ClaveSegura123");
        user.setFailedLoginAttempts(4);
        user.setLastFailedLoginAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        when(users.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(user));
        when(users.save(any(BackofficeUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.login("admin", "ClaveIncorrecta999", response))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getLastFailedLoginAt()).isNotNull();
    }

    @Test
    void fiveFailuresInsideObservationWindowLockAccount() {
        BackofficeUser user = user("admin", true, "ClaveSegura123");
        user.setFailedLoginAttempts(4);
        user.setLastFailedLoginAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(users.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(user));
        when(users.save(any(BackofficeUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.login("admin", "ClaveIncorrecta999", response))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Test
    void oversizedCurrentPasswordIsRejectedWithoutCallingBcryptMatchesOnIt() {
        BackofficeUser user = user("admin", true, "ClaveSegura123");
        BackofficePrincipal principal = BackofficePrincipal.from(user, 7L, "csrf", true);
        when(users.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changeOwnPassword(
                principal, "A".repeat(80), "NuevaClaveSegura123", response))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void oversizedBcryptInputFailsBeforeUserLookup() {
        String oversized = "A".repeat(80) + "a1";

        assertThatThrownBy(() -> service.login("admin", oversized, response))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(401));
        verifyNoInteractions(users);
    }

    private BackofficeUser user(String username, boolean active, String password) {
        return BackofficeUser.builder()
                .id(1L)
                .username(username)
                .displayName("Admin")
                .passwordHash(encoder.encode(password))
                .role(BackofficeRole.ADMIN)
                .active(active)
                .sessionVersion(1)
                .build();
    }

    private String reasonOf(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Se esperaba fallo de autenticación");
        } catch (ResponseStatusException ex) {
            assertThat(ex.getStatusCode().value()).isEqualTo(401);
            return ex.getReason();
        }
    }
}
