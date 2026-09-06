package uy.pensiones.service;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uy.pensiones.enums.BackofficeRole;
import uy.pensiones.model.BackofficeSession;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.repo.BackofficeSessionRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BackofficeSessionServiceTest {

    @Mock BackofficeSessionRepository repository;
    @Mock HttpServletResponse response;
    private BackofficeSessionService service;

    @BeforeEach
    void setUp() {
        service = new BackofficeSessionService(repository, Duration.ofHours(8), Duration.ofMinutes(30),
                "PENSIONES_BACKOFFICE_SESSION", "/api", false, "Strict", new BackofficeMfaPolicy(false, "Pensiones Backoffice"));
    }

    @Test
    void expiresSessionAfterIdleTimeoutEvenWhenAbsoluteTtlIsStillValid() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        BackofficeSession session = session(now.minusHours(2), now.minusMinutes(31), now.plusHours(6));
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(session));

        assertThat(service.authenticate("opaque-token")).isEmpty();
        verify(repository).delete(session);
    }

    @Test
    void keepsRecentlyActiveSessionAndReturnsBackofficePrincipal() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        BackofficeSession session = session(now.minusHours(2), now.minusMinutes(3), now.plusHours(6));
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(session));

        var principal = service.authenticate("opaque-token");

        assertThat(principal).isPresent();
        assertThat(principal.orElseThrow().username()).isEqualTo("admin");
        verify(repository, never()).delete(any());
    }

    @Test
    void adminSessionStartsRestrictedWhenMfaIsRequiredButNotEnrolled() {
        BackofficeSessionService mfaService = new BackofficeSessionService(repository, Duration.ofHours(8), Duration.ofMinutes(30),
                "PENSIONES_BACKOFFICE_SESSION", "/api", false, "Strict", new BackofficeMfaPolicy(true, "Pensiones Backoffice"));
        BackofficeUser user = BackofficeUser.builder().id(7L).username("admin").displayName("Admin")
                .passwordHash("unused").role(BackofficeRole.ADMIN).active(true).sessionVersion(2).build();
        when(repository.save(any(BackofficeSession.class))).thenAnswer(invocation -> {
            BackofficeSession value = invocation.getArgument(0);
            value.setId(11L);
            return value;
        });

        var credentials = mfaService.create(user, response);

        assertThat(credentials.principal().mfaSatisfied()).isFalse();
        assertThat(credentials.principal().authorities()).extracting(Object::toString)
                .containsExactly("ROLE_BACKOFFICE_AUTHENTICATED");
    }

    @Test
    void rejectsIdleTimeoutLongerThanAbsoluteTtl() {
        assertThatThrownBy(() -> new BackofficeSessionService(repository, Duration.ofMinutes(30), Duration.ofHours(1),
                "SESSION", "/api", false, "Strict", new BackofficeMfaPolicy(false, "Pensiones Backoffice")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idle-timeout");
    }

    private BackofficeSession session(OffsetDateTime createdAt, OffsetDateTime lastSeenAt, OffsetDateTime expiresAt) {
        BackofficeUser user = BackofficeUser.builder()
                .id(7L)
                .username("admin")
                .displayName("Admin")
                .passwordHash("unused")
                .role(BackofficeRole.ADMIN)
                .active(true)
                .sessionVersion(2)
                .build();
        return BackofficeSession.builder()
                .id(11L)
                .backofficeUser(user)
                .tokenHash("hash")
                .csrfToken("csrf")
                .sessionVersion(2)
                .createdAt(createdAt)
                .lastSeenAt(lastSeenAt)
                .expiresAt(expiresAt)
                .build();
    }
}
