package uy.pensiones.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.service.DashboardService;
import uy.pensiones.web.dto.DashboardDTO;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboard;
    private final UserRepository users;

    public DashboardController(DashboardService dashboard, UserRepository users) {
        this.dashboard = dashboard;
        this.users = users;
    }

    @GetMapping
    public DashboardDTO get(@AuthenticationPrincipal OAuth2User principal) {
        return dashboard.build(current(principal).getId());
    }

    private User current(OAuth2User principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No autenticado");
        }

        User appUser = principal.getAttribute("appUser");
        if (appUser != null) {
            return users.findById(appUser.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
        }

        String email = principal.getAttribute("email");
        if (email == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado");
        }
        return users.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
    }
}
