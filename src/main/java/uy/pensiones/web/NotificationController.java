package uy.pensiones.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserNotificationRepository;
import uy.pensiones.repo.UserRepository;
import uy.pensiones.web.dto.UserNotificationDTO;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    public record NotificationCenterDTO(long unreadCount, List<UserNotificationDTO> items) {}

    private final UserNotificationRepository notifications;
    private final UserRepository users;

    public NotificationController(UserNotificationRepository notifications, UserRepository users) {
        this.notifications = notifications;
        this.users = users;
    }

    @GetMapping
    public NotificationCenterDTO list(@AuthenticationPrincipal OAuth2User principal,
                                      @RequestParam(defaultValue = "20") int limit) {
        User me = current(principal);
        int safeLimit = Math.max(1, Math.min(limit, 50));
        List<UserNotificationDTO> items = notifications
                .findByUserIdOrderByCreatedAtDesc(me.getId(), PageRequest.of(0, safeLimit))
                .stream()
                .map(UserNotificationDTO::of)
                .toList();
        return new NotificationCenterDTO(
                notifications.countByUserIdAndReadAtIsNull(me.getId()),
                items
        );
    }

    @Transactional
    @PatchMapping("/{id}/read")
    public UserNotificationDTO markRead(@AuthenticationPrincipal OAuth2User principal,
                                        @PathVariable Long id) {
        User me = current(principal);
        var notification = notifications.findByIdAndUserId(id, me.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notificación no encontrada"));
        if (notification.getReadAt() == null) {
            notification.setReadAt(OffsetDateTime.now());
            notification = notifications.save(notification);
        }
        return UserNotificationDTO.of(notification);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal OAuth2User principal) {
        User me = current(principal);
        notifications.markAllRead(me.getId(), OffsetDateTime.now());
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
        return users.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
    }
}
