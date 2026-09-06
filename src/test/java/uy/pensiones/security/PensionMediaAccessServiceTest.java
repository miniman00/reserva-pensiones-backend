package uy.pensiones.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import uy.pensiones.repo.PensionRepository;
import uy.pensiones.service.BackofficeSessionService;
import uy.pensiones.service.PensionCatalogQualityService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PensionMediaAccessServiceTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void publishedKnownFileIsPublicButPrivateStateIsNotAnonymous() {
        PensionRepository pensions = mock(PensionRepository.class);
        Authz authz = mock(Authz.class);
        BackofficeSessionService sessions = mock(BackofficeSessionService.class);
        PensionCatalogQualityService quality = mock(PensionCatalogQualityService.class);
        java.time.OffsetDateTime cutoff = java.time.OffsetDateTime.parse("2026-07-01T00:00:00Z");
        when(quality.publicAvailabilityCutoff()).thenReturn(cutoff);
        PensionMediaAccessService service = new PensionMediaAccessService(pensions, authz, sessions, quality);

        PensionRepository.MediaAccessState published = state(true, true);
        when(pensions.findMediaAccessState(42L, "photo.jpg", cutoff)).thenReturn(Optional.of(published));
        assertThat(service.accessLevel(new MockHttpServletRequest(), 42L, "photo.jpg"))
                .isEqualTo(PensionMediaAccessService.AccessLevel.PUBLIC);

        PensionRepository.MediaAccessState privateState = state(true, false);
        when(pensions.findMediaAccessState(42L, "photo.jpg", cutoff)).thenReturn(Optional.of(privateState));
        assertThat(service.accessLevel(new MockHttpServletRequest(), 42L, "photo.jpg"))
                .isEqualTo(PensionMediaAccessService.AccessLevel.DENIED);
    }

    @Test
    void authorizedBackofficePreviewIsPrivateNotPublic() {
        PensionRepository pensions = mock(PensionRepository.class);
        Authz authz = mock(Authz.class);
        BackofficeSessionService sessions = mock(BackofficeSessionService.class);
        PensionCatalogQualityService quality = mock(PensionCatalogQualityService.class);
        java.time.OffsetDateTime cutoff = java.time.OffsetDateTime.parse("2026-07-01T00:00:00Z");
        when(quality.publicAvailabilityCutoff()).thenReturn(cutoff);
        PensionMediaAccessService service = new PensionMediaAccessService(pensions, authz, sessions, quality);
        MockHttpServletRequest request = new MockHttpServletRequest();

        PensionRepository.MediaAccessState privateState = state(true, false);
        when(pensions.findMediaAccessState(42L, "photo.jpg", cutoff)).thenReturn(Optional.of(privateState));
        BackofficePrincipal principal = mock(BackofficePrincipal.class);
        when(sessions.readToken(request)).thenReturn("opaque-token");
        when(sessions.authenticate("opaque-token"))
                .thenReturn(Optional.of(principal));

        assertThat(service.accessLevel(request, 42L, "photo.jpg"))
                .isEqualTo(PensionMediaAccessService.AccessLevel.PRIVATE);
    }

    @Test
    void filenameMustBelongToPension() {
        PensionRepository pensions = mock(PensionRepository.class);
        Authz authz = mock(Authz.class);
        BackofficeSessionService sessions = mock(BackofficeSessionService.class);
        PensionCatalogQualityService quality = mock(PensionCatalogQualityService.class);
        java.time.OffsetDateTime cutoff = java.time.OffsetDateTime.parse("2026-07-01T00:00:00Z");
        when(quality.publicAvailabilityCutoff()).thenReturn(cutoff);
        PensionMediaAccessService service = new PensionMediaAccessService(pensions, authz, sessions, quality);
        PensionRepository.MediaAccessState unknownFile = state(false, true);
        when(pensions.findMediaAccessState(42L, "secret.txt", cutoff)).thenReturn(Optional.of(unknownFile));

        assertThat(service.accessLevel(new MockHttpServletRequest(), 42L, "secret.txt"))
                .isEqualTo(PensionMediaAccessService.AccessLevel.DENIED);
        verifyNoInteractions(authz);
    }

    private PensionRepository.MediaAccessState state(boolean known, boolean publicVisible) {
        PensionRepository.MediaAccessState state = mock(PensionRepository.MediaAccessState.class);
        when(state.getKnownFile()).thenReturn(known);
        when(state.getPublicVisible()).thenReturn(publicVisible);
        return state;
    }
}
