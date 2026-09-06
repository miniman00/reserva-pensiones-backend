package uy.pensiones.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.model.BackofficeUser;
import uy.pensiones.repo.BackofficeUserRepository;
import uy.pensiones.security.BackofficePrincipal;

@Service
public class BackofficeAccessService {

    private final BackofficeUserRepository users;

    public BackofficeAccessService(BackofficeUserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public BackofficeUser requireAdmin(BackofficePrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No hay una sesión de Backoffice activa");
        }
        return users.findById(principal.id())
                .filter(BackofficeUser::isActive)
                .filter(user -> user.getSessionVersion() == principal.sessionVersion())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "La sesión de Backoffice ya no es válida"));
    }
}
