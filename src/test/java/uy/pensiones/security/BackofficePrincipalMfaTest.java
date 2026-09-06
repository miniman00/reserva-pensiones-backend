package uy.pensiones.security;

import org.junit.jupiter.api.Test;
import uy.pensiones.enums.BackofficeRole;
import uy.pensiones.model.BackofficeUser;

import static org.assertj.core.api.Assertions.assertThat;

class BackofficePrincipalMfaTest {

    @Test
    void privilegedAuthoritiesRemainUnavailableUntilMfaIsSatisfied() {
        BackofficeUser user = BackofficeUser.builder().id(1L).username("admin").displayName("Admin")
                .passwordHash("hash").role(BackofficeRole.ADMIN).active(true).sessionVersion(1).build();

        BackofficePrincipal pending = BackofficePrincipal.from(user, 2L, "csrf", false);
        BackofficePrincipal verified = BackofficePrincipal.from(user, 2L, "csrf", true);

        assertThat(pending.authorities()).extracting(Object::toString)
                .containsExactly("ROLE_BACKOFFICE_AUTHENTICATED");
        assertThat(verified.authorities()).extracting(Object::toString)
                .contains("ROLE_BACKOFFICE", "BACKOFFICE_COMMERCIAL_MANAGE");
    }
}
