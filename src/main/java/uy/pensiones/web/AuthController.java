package uy.pensiones.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uy.pensiones.service.EmailVerificationService;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final EmailVerificationService emailVerification;
    private final UserRepository users;

    public AuthController(EmailVerificationService emailVerification, UserRepository users) {
        this.emailVerification = emailVerification;
        this.users = users;
    }

    private User current(@AuthenticationPrincipal OAuth2User p) {
        if (p == null) return null;
        User u = (User) p.getAttribute("appUser");
        if (u != null) return u;
        String email = p.getAttribute("email");
        return users.findByEmail(email).orElse(null);
    }


    /**
     * Entrega el token antifalsificación de la sesión actual. También funciona para
     * visitantes anónimos que necesitan realizar una mutación pública (consulta,
     * reporte, view/exposure), evitando excepciones de CSRF por conveniencia.
     */
    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken csrfToken) {
        return csrfToken;
    }

    @GetMapping("/verify-email")
    public void verifyEmail(@AuthenticationPrincipal OAuth2User principal,
                            @RequestParam("token") String token) {
        User me = current(principal);
        emailVerification.verify(token, me.getId());
    }
}
