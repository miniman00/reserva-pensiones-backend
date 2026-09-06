package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.BackofficeRole;
import uy.pensiones.model.BackofficeSession;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.repo.BackofficeSessionRepository;
import uy.pensiones.security.BackofficePrincipal;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BackofficeReauthenticationServiceTest {

    @Mock BackofficeSessionRepository sessions;
    @Mock BackofficeSystemSuperAdminService systemAdmin;
    private PasswordEncoder encoder;
    private BackofficeReauthenticationService service;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder(4);
        service = new BackofficeReauthenticationService(sessions, encoder, systemAdmin, Duration.ofMinutes(10));
    }

    @Test
    void recentAuthenticationAllowsSensitiveAction() {
        BackofficeSession session = session(false, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(3));
        when(sessions.findById(11L)).thenReturn(Optional.of(session));

        service.requireRecent(principal(session));
    }

    @Test
    void staleAuthenticationReturnsPreconditionRequired() {
        BackofficeSession session = session(false, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(11));
        when(sessions.findById(11L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.requireRecent(principal(session)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(428));
    }

    @Test
    void validPasswordRefreshesStepUpWindow() {
        BackofficeSession session = session(false, OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        when(sessions.findById(11L)).thenReturn(Optional.of(session));
        when(sessions.save(any(BackofficeSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var status = service.reauthenticate(principal(session), "ClaveSegura123");

        assertThat(status.fresh()).isTrue();
        assertThat(status.reauthenticationExpiresAt()).isAfter(status.reauthenticatedAt());
        assertThat(session.getReauthFailedAttempts()).isZero();
        assertThat(session.getReauthLockedUntil()).isNull();
        verify(sessions).save(session);
    }

    @Test
    void invalidPasswordDoesNotRefreshStepUpWindow() {
        OffsetDateTime previous = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        BackofficeSession session = session(false, previous);
        when(sessions.findById(11L)).thenReturn(Optional.of(session));
        when(sessions.save(any(BackofficeSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.reauthenticate(principal(session), "ClaveIncorrecta999"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));

        assertThat(session.getLastReauthenticatedAt()).isEqualTo(previous);
        assertThat(session.getReauthFailedAttempts()).isEqualTo(1);
    }

    @Test
    void fiveInvalidConfirmationsTemporarilyLockStepUpAuthentication() {
        BackofficeSession session = session(false, OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        session.setReauthFailedAttempts(4);
        when(sessions.findById(11L)).thenReturn(Optional.of(session));
        when(sessions.save(any(BackofficeSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.reauthenticate(principal(session), "ClaveIncorrecta999"))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(session.getReauthFailedAttempts()).isZero();
        assertThat(session.getReauthLockedUntil()).isAfter(OffsetDateTime.now(ZoneOffset.UTC));

        assertThatThrownBy(() -> service.reauthenticate(principal(session), "ClaveSegura123"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(429));
    }

    @Test
    void systemManagedAccountUsesServerCredential() {
        BackofficeSession session = session(true, OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        when(sessions.findById(11L)).thenReturn(Optional.of(session));
        when(sessions.save(any(BackofficeSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(systemAdmin.isConfigured()).thenReturn(true);
        when(systemAdmin.isSystemUsername("soporte")).thenReturn(true);
        when(systemAdmin.matches("ClaveServidor123")).thenReturn(true);

        var status = service.reauthenticate(principal(session), "ClaveServidor123");

        assertThat(status.fresh()).isTrue();
        verify(systemAdmin).matches("ClaveServidor123");
    }

    @Test
    void rejectsReauthenticationTtlLongerThanOneHour() {
        assertThatThrownBy(() -> new BackofficeReauthenticationService(
                sessions, encoder, systemAdmin, Duration.ofHours(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reauthentication.ttl");
    }

    private BackofficeSession session(boolean systemManaged, OffsetDateTime lastReauthenticatedAt) {
        String username = systemManaged ? "soporte" : "admin";
        BackofficeUser user = BackofficeUser.builder()
                .id(7L)
                .username(username)
                .displayName("Admin")
                .passwordHash(encoder.encode("ClaveSegura123"))
                .role(BackofficeRole.SUPER_ADMIN)
                .active(true)
                .systemManaged(systemManaged)
                .sessionVersion(2)
                .build();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return BackofficeSession.builder()
                .id(11L)
                .backofficeUser(user)
                .tokenHash("hash")
                .csrfToken("csrf")
                .sessionVersion(2)
                .createdAt(now.minusHours(2))
                .lastSeenAt(now.minusMinutes(1))
                .lastReauthenticatedAt(lastReauthenticatedAt)
                .reauthFailedAttempts(0)
                .expiresAt(now.plusHours(6))
                .build();
    }

    private BackofficePrincipal principal(BackofficeSession session) {
        return BackofficePrincipal.from(session.getBackofficeUser(), session.getId(), session.getCsrfToken(), true);
    }
}
