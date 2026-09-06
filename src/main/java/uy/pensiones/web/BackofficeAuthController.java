package uy.pensiones.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.security.BackofficePrincipal;
import uy.pensiones.service.BackofficeAuthenticationService;
import uy.pensiones.service.BackofficeLoginRateLimiter;
import uy.pensiones.service.BackofficeMfaService;
import uy.pensiones.service.BackofficeMfaBreakGlassService;
import uy.pensiones.service.BackofficeReauthenticationService;
import uy.pensiones.service.BackofficeSessionService;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/backoffice/auth")
public class BackofficeAuthController {

    private final BackofficeAuthenticationService auth;
    private final BackofficeLoginRateLimiter loginRateLimiter;
    private final BackofficeReauthenticationService reauthentication;
    private final BackofficeMfaService mfa;
    private final BackofficeMfaBreakGlassService breakGlass;

    public BackofficeAuthController(BackofficeAuthenticationService auth,
                                    BackofficeLoginRateLimiter loginRateLimiter,
                                    BackofficeReauthenticationService reauthentication,
                                    BackofficeMfaService mfa,
                                    BackofficeMfaBreakGlassService breakGlass) {
        this.auth = auth;
        this.loginRateLimiter = loginRateLimiter;
        this.reauthentication = reauthentication;
        this.mfa = mfa;
        this.breakGlass = breakGlass;
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BackofficeSessionDTO login(@Valid @RequestBody LoginRequest request,
                                      HttpServletRequest servletRequest,
                                      HttpServletResponse response) {
        loginRateLimiter.checkAndRecord(servletRequest, request.username());
        BackofficeSessionService.SessionCredentials credentials = auth.login(request.username(), request.password(), response);
        return toDto(auth.current(credentials.principal()), credentials.principal(), credentials.csrfToken());
    }

    @GetMapping("/me")
    public BackofficeSessionDTO me(@AuthenticationPrincipal BackofficePrincipal principal) {
        return toDto(auth.current(principal), principal, principal.csrfToken());
    }

    @PostMapping(value = "/reauthenticate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReauthenticationDTO reauthenticate(@AuthenticationPrincipal BackofficePrincipal principal,
                                               @Valid @RequestBody ReauthenticationRequest request) {
        var status = reauthentication.reauthenticate(principal, request.password());
        return new ReauthenticationDTO(status.reauthenticatedAt(), status.reauthenticationExpiresAt(), status.fresh());
    }

    @PostMapping("/mfa/setup")
    public BackofficeMfaService.Enrollment beginMfaSetup(@AuthenticationPrincipal BackofficePrincipal principal) {
        return mfa.beginEnrollment(principal);
    }

    @PostMapping(value = "/mfa/setup/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BackofficeMfaService.EnrollmentResult confirmMfaSetup(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @Valid @RequestBody MfaCodeRequest request) {
        return mfa.confirmEnrollment(principal, request.code());
    }

    @PostMapping(value = "/mfa/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BackofficeMfaService.MfaStatus verifyMfa(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @Valid @RequestBody MfaCodeRequest request) {
        return mfa.verify(principal, request.code());
    }


    @GetMapping("/mfa/break-glass")
    public BackofficeMfaBreakGlassService.Status breakGlassStatus(
            @AuthenticationPrincipal BackofficePrincipal principal) {
        return breakGlass.status(principal);
    }

    @PostMapping(value = "/mfa/break-glass", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BackofficeMfaBreakGlassService.Status useBreakGlass(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @Valid @RequestBody BreakGlassRequest request) {
        return breakGlass.consume(principal, request.token(), request.reason());
    }

    @PostMapping(value = "/mfa/recovery-codes/regenerate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BackofficeMfaService.RecoveryCodesResult regenerateRecoveryCodes(
            @AuthenticationPrincipal BackofficePrincipal principal,
            @Valid @RequestBody MfaCodeRequest request) {
        return mfa.regenerateRecoveryCodes(principal, request.code());
    }

    @PostMapping(value = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BackofficeSessionDTO changePassword(@AuthenticationPrincipal BackofficePrincipal principal,
                                               @Valid @RequestBody ChangePasswordRequest request,
                                               HttpServletResponse response) {
        BackofficeSessionService.SessionCredentials credentials = auth.changeOwnPassword(
                principal,
                request.currentPassword(),
                request.newPassword(),
                response
        );
        return toDto(auth.current(credentials.principal()), credentials.principal(), credentials.csrfToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal BackofficePrincipal principal,
                                       HttpServletResponse response) {
        auth.logout(principal, response);
        return ResponseEntity.noContent().build();
    }

    private BackofficeSessionDTO toDto(BackofficeUser user, BackofficePrincipal principal, String csrfToken) {
        var reauth = reauthentication.status(principal);
        var mfaStatus = mfa.status(principal);
        return new BackofficeSessionDTO(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getEmail(),
                user.getRole().name(),
                user.isMustChangePassword(),
                user.isSystemManaged(),
                user.getLastLoginAt(),
                principal.authorities().stream().map(a -> a.getAuthority()).toList(),
                reauth.reauthenticatedAt(),
                reauth.reauthenticationExpiresAt(),
                mfaStatus.featureEnabled(),
                mfaStatus.required(),
                mfaStatus.enabled(),
                mfaStatus.verified(),
                mfaStatus.enrollmentRequired(),
                mfaStatus.verificationRequired(),
                mfaStatus.recoveryCodesAvailable(),
                mfaStatus.enabledAt(),
                mfaStatus.lastVerifiedAt(),
                csrfToken
        );
    }

    public record LoginRequest(
            @NotBlank @Size(max = 80) String username,
            @NotBlank @Size(max = 256) String password
    ) {}
    public record ReauthenticationRequest(@NotBlank @Size(max = 256) String password) {}
    public record MfaCodeRequest(@NotBlank @Size(max = 64) String code) {}
    public record BreakGlassRequest(
            @NotBlank @Size(min = 32, max = 72) String token,
            @NotBlank @Size(min = 10, max = 1500) String reason
    ) {}
    public record ReauthenticationDTO(OffsetDateTime reauthenticatedAt,
                                      OffsetDateTime reauthenticationExpiresAt,
                                      boolean fresh) {}
    public record ChangePasswordRequest(
            @NotBlank @Size(max = 256) String currentPassword,
            @NotBlank @Size(max = 256) String newPassword
    ) {}
    public record BackofficeSessionDTO(
            Long id,
            String username,
            String displayName,
            String email,
            String role,
            boolean mustChangePassword,
            boolean systemManaged,
            OffsetDateTime lastLoginAt,
            List<String> permissions,
            OffsetDateTime reauthenticatedAt,
            OffsetDateTime reauthenticationExpiresAt,
            boolean mfaFeatureEnabled,
            boolean mfaRequired,
            boolean mfaEnabled,
            boolean mfaVerified,
            boolean mfaEnrollmentRequired,
            boolean mfaVerificationRequired,
            long mfaRecoveryCodesAvailable,
            OffsetDateTime mfaEnabledAt,
            OffsetDateTime mfaLastVerifiedAt,
            String csrfToken
    ) {}
}
