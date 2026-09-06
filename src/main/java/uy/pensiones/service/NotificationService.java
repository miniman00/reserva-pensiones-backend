package uy.pensiones.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.enums.NotificationType;
import uy.pensiones.model.User;
import uy.pensiones.model.UserNotification;
import uy.pensiones.repo.UserNotificationRepository;
import uy.pensiones.repo.UserRepository;

@Service
public class NotificationService {

    private final UserNotificationRepository notifications;
    private final UserRepository users;

    public NotificationService(UserNotificationRepository notifications, UserRepository users) {
        this.notifications = notifications;
        this.users = users;
    }

    @Transactional
    public void create(User recipient, NotificationType type, String title, String message, String link) {
        if (recipient == null || recipient.getId() == null) return;

        User managed = users.findById(recipient.getId()).orElse(null);
        if (managed == null) return;

        notifications.save(UserNotification.builder()
                .user(managed)
                .type(type)
                .title(trim(title, 180))
                .message(trim(message, 700))
                .link(trim(link, 500))
                .build());
    }

    @Transactional
    public boolean createOnce(User recipient, NotificationType type, String title, String message,
                              String link, String dedupKey) {
        if (recipient == null || recipient.getId() == null || dedupKey == null || dedupKey.isBlank()) return false;
        String key = trim(dedupKey, 190);
        int inserted = notifications.insertDeduplicated(
                recipient.getId(),
                type.name(),
                trim(title, 180),
                trim(message, 700),
                trim(link, 500),
                key,
                java.time.OffsetDateTime.now()
        );
        return inserted > 0;
    }

    @Transactional
    public void createForEmail(String email, NotificationType type, String title, String message, String link) {
        if (email == null || email.isBlank()) return;
        users.findByEmail(email.trim().toLowerCase())
                .ifPresent(user -> create(user, type, title, message, link));
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        return v.length() <= max ? v : v.substring(0, max);
    }
}
