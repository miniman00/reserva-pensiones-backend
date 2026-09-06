package uy.pensiones.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.user.OAuth2User;
import uy.pensiones.enums.PensionRole;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionMember;
import uy.pensiones.model.User;
import uy.pensiones.repo.MembershipRepository;
import uy.pensiones.repo.PensionMemberRepository;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.repo.UserRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthzTest {

    @Mock private MembershipRepository memberships;
    @Mock private PensionRepository pensions;
    @Mock private UserRepository users;
    @Mock private PensionMemberRepository members;

    private Authz authz;

    @BeforeEach
    void setUp() {
        authz = new Authz(memberships, pensions, users, members);
    }

    @Test
    void explicitOwnerCanEditAndManageMembers() {
        User owner = user(1L);
        Pension pension = Pension.builder().id(20L).owner(owner).build();
        when(pensions.findById(20L)).thenReturn(Optional.of(pension));
        OAuth2User principal = principal(owner);

        assertThat(authz.canEditPensionFields(principal, 20L)).isTrue();
        assertThat(authz.canManageMembers(principal, 20L)).isTrue();
        assertThat(authz.canUpdateAvailability(principal, 20L)).isTrue();
    }

    @Test
    void availabilityOnlyMemberCanUpdateAvailabilityButCannotEditGeneralFields() {
        User owner = user(1L);
        User collaborator = user(2L);
        Pension pension = Pension.builder().id(20L).owner(owner).build();
        PensionMember member = new PensionMember();
        member.setPension(pension);
        member.setUser(collaborator);
        member.setRole(PensionRole.AVAIL_ONLY);

        when(pensions.findById(20L)).thenReturn(Optional.of(pension));
        when(members.findByPensionIdAndUserId(20L, 2L)).thenReturn(Optional.of(member));
        OAuth2User principal = principal(collaborator);

        assertThat(authz.canUpdateAvailability(principal, 20L)).isTrue();
        assertThat(authz.canViewPension(principal, 20L)).isTrue();
        assertThat(authz.canEditPensionFields(principal, 20L)).isFalse();
        assertThat(authz.canManageMembers(principal, 20L)).isFalse();
    }

    @Test
    void unrelatedUserCannotUseLegacyOrgPermissionsWhenExplicitOwnerExists() {
        User owner = user(1L);
        User unrelated = user(3L);
        Pension pension = Pension.builder().id(20L).owner(owner).build();
        when(pensions.findById(20L)).thenReturn(Optional.of(pension));
        when(members.findByPensionIdAndUserId(20L, 3L)).thenReturn(Optional.empty());

        assertThat(authz.canUpdateAvailability(3L, 20L)).isFalse();
        assertThat(authz.canViewPension(principal(unrelated), 20L)).isFalse();
    }

    @Test
    void nullPrincipalNeverGetsAccess() {
        assertThat(authz.canViewPension(null, 999L)).isFalse();
        assertThat(authz.canUpdateAvailability((OAuth2User) null, 999L)).isFalse();
    }

    private User user(Long id) {
        return User.builder().id(id).email("u" + id + "@example.test").build();
    }

    @SuppressWarnings("unchecked")
    private OAuth2User principal(User user) {
        OAuth2User principal = mock(OAuth2User.class);
        when(principal.getAttribute("appUser")).thenReturn(user);
        return principal;
    }
}
