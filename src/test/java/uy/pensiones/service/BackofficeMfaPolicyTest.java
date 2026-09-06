package uy.pensiones.service;

import org.junit.jupiter.api.Test;
import uy.pensiones.enums.BackofficeRole;
import uy.pensiones.model.BackofficeSession;
import uy.pensiones.model.BackofficeUser;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BackofficeMfaPolicyTest {

    private final BackofficeMfaPolicy policy = new BackofficeMfaPolicy(true, "Pensiones Backoffice");

    @Test
    void requiresEnrollmentForAdminAndSuperAdmin() {
        BackofficeUser admin = user(BackofficeRole.ADMIN);
        BackofficeUser superAdmin = user(BackofficeRole.SUPER_ADMIN);
        BackofficeUser moderator = user(BackofficeRole.MODERATOR);

        assertThat(policy.enrollmentRequired(admin)).isTrue();
        assertThat(policy.enrollmentRequired(superAdmin)).isTrue();
        assertThat(policy.enrollmentRequired(moderator)).isFalse();
    }

    @Test
    void enrolledUserNeedsVerifiedSessionEvenIfRoleIsNotMandatory() {
        BackofficeUser moderator = user(BackofficeRole.MODERATOR);
        moderator.setMfaEnabledAt(OffsetDateTime.now());
        moderator.setMfaSecretCiphertext("encrypted");
        BackofficeSession session = BackofficeSession.builder().backofficeUser(moderator).build();

        assertThat(policy.satisfied(moderator, session)).isFalse();
        session.setMfaVerifiedAt(OffsetDateTime.now());
        assertThat(policy.satisfied(moderator, session)).isTrue();
    }

    private BackofficeUser user(BackofficeRole role) {
        return BackofficeUser.builder().id(1L).username("user").displayName("User")
                .passwordHash("hash").role(role).active(true).sessionVersion(1).build();
    }
}
