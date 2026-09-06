package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import uy.pensiones.enums.BackofficeRole;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.BackofficeSession;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.repo.BackofficeMfaRecoveryCodeRepository;
import uy.pensiones.repo.BackofficeSessionRepository;
import uy.pensiones.repo.BackofficeUserRepository;
import uy.pensiones.security.BackofficePrincipal;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackofficeMfaBreakGlassServiceTest {

    @Test
    void disabledConfigurationDoesNotRequireEmergencySecrets() {
        assertDoesNotThrow(() -> service(false, "not-a-bcrypt", "bad-date", mocks()));
    }

    @Test
    void enabledConfigurationRejectsWindowLongerThan24Hours() {
        Dependencies d = mocks();
        when(d.systemAdmin.isConfigured()).thenReturn(true);
        String hash = new BCryptPasswordEncoder(12).encode("abcdefghijklmnopqrstuvwxyz1234567890");
        assertThrows(IllegalStateException.class, () -> service(true, hash,
                OffsetDateTime.now().plusHours(25).toString(), d));
    }

    @Test
    void consumesCredentialOnceAndForcesNewMfaEnrollment() {
        Dependencies d = mocks();
        when(d.systemAdmin.isConfigured()).thenReturn(true);
        when(d.systemAdmin.isSystemUsername("soporte")).thenReturn(true);
        String raw = "abcdefghijklmnopqrstuvwxyz1234567890";
        PasswordEncoder encoder = new BCryptPasswordEncoder(4);
        String hash = encoder.encode(raw);
        BackofficeUser user = BackofficeUser.builder().id(1L).username("soporte").displayName("Soporte")
                .email("soporte@example.com").passwordHash("managed").role(BackofficeRole.SUPER_ADMIN)
                .systemManaged(true).active(true).sessionVersion(1).mfaSecretCiphertext("enc")
                .mfaEnabledAt(OffsetDateTime.now().minusDays(1)).build();
        BackofficeSession session = BackofficeSession.builder().id(7L).backofficeUser(user).sessionVersion(1)
                .mfaVerifiedAt(OffsetDateTime.now()).build();
        BackofficePrincipal principal = BackofficePrincipal.from(user, 7L, "csrf", false);
        when(d.users.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(d.users.findById(1L)).thenReturn(Optional.of(user));
        when(d.sessions.findById(7L)).thenReturn(Optional.of(session));
        when(d.mfaPolicy.enrolled(user)).thenReturn(true, false);
        when(d.audit.requireReason(anyString())).thenAnswer(i -> i.getArgument(0));
        when(d.jdbc.queryForObject(startsWith("select exists"), eq(Boolean.class), any())).thenReturn(false, true);
        when(d.jdbc.update(startsWith("insert into backoffice_mfa_break_glass_uses"), any(), any(), any(), any())).thenReturn(1);

        var svc = new BackofficeMfaBreakGlassService(encoder, d.systemAdmin, d.users, d.sessions,
                d.recoveryCodes, d.mfaPolicy, d.reauth, d.audit, d.mail, d.jdbc,
                true, hash, OffsetDateTime.now().plusHours(1).toString());
        var status = svc.consume(principal, raw, "INC-1234 recuperación MFA autorizada");

        assertThat(status.credentialUsed()).isTrue();
        assertThat(user.getMfaSecretCiphertext()).isNull();
        assertThat(user.getMfaEnabledAt()).isNull();
        assertThat(session.getMfaVerifiedAt()).isNull();
        verify(d.recoveryCodes).deleteByBackofficeUser_Id(1L);
    }

    private BackofficeMfaBreakGlassService service(boolean enabled, String hash, String expires, Dependencies d) {
        return new BackofficeMfaBreakGlassService(d.encoder, d.systemAdmin, d.users, d.sessions, d.recoveryCodes,
                d.mfaPolicy, d.reauth, d.audit, d.mail, d.jdbc, enabled, hash, expires);
    }

    private Dependencies mocks() {
        return new Dependencies(mock(PasswordEncoder.class), mock(BackofficeSystemSuperAdminService.class),
                mock(BackofficeUserRepository.class), mock(BackofficeSessionRepository.class),
                mock(BackofficeMfaRecoveryCodeRepository.class), mock(BackofficeMfaPolicy.class),
                mock(BackofficeReauthenticationService.class), mock(AdminAuditService.class),
                mock(MailService.class), mock(JdbcTemplate.class));
    }

    private record Dependencies(PasswordEncoder encoder, BackofficeSystemSuperAdminService systemAdmin,
                                BackofficeUserRepository users, BackofficeSessionRepository sessions,
                                BackofficeMfaRecoveryCodeRepository recoveryCodes, BackofficeMfaPolicy mfaPolicy,
                                BackofficeReauthenticationService reauth, AdminAuditService audit,
                                MailService mail, JdbcTemplate jdbc) {}
}
