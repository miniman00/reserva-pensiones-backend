package uy.pensiones.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.enums.NotificationType;
import uy.pensiones.model.User;
import uy.pensiones.model.UserNotification;
import uy.pensiones.realtime.RealtimeEventService;
import uy.pensiones.repo.UserNotificationRepository;
import uy.pensiones.repo.UserRepository;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class NotificationService {

    private final UserNotificationRepository notifications;
    private final UserRepository users;
    private final RealtimeEventService realtimeEvents;

    public NotificationService(UserNotificationRepository notifications,
                               UserRepository users,
                               RealtimeEventService realtimeEvents) {
        this.notifications = notifications;
        this.users = users;
        this.realtimeEvents = realtimeEvents;
    }

    @Transactional
    public void create(User recipient, NotificationType type, String title, String message, String link) {
        if (recipient == null || recipient.getId() == null) return;

        User managed = users.findById(recipient.getId()).orElse(null);
        if (managed == null) return;

        UserNotification saved = notifications.save(UserNotification.builder()
                .user(managed)
                .type(type)
                .title(trim(title, 180))
                .message(trim(message, 700))
                .link(trim(link, 500))
                .build());
        publishCreated(managed.getId(), saved.getId(), type, saved.getLink());
    }

    @Transactional
    public boolean createOnce(User recipient, NotificationType type, String title, String message,
                              String link, String dedupKey) {
        if (recipient == null || recipient.getId() == null || dedupKey == null || dedupKey.isBlank()) return false;
        String key = trim(dedupKey, 190);
        String safeLink = trim(link, 500);
        int inserted = notifications.insertDeduplicated(
                recipient.getId(),
                type.name(),
                trim(title, 180),
                trim(message, 700),
                safeLink,
                key,
                java.time.OffsetDateTime.now()
        );
        if (inserted > 0) publishCreated(recipient.getId(), null, type, safeLink);
        return inserted > 0;
    }

    @Transactional
    public void createForEmail(String email, NotificationType type, String title, String message, String link) {
        if (email == null || email.isBlank()) return;
        users.findByEmail(email.trim().toLowerCase())
                .ifPresent(user -> create(user, type, title, message, link));
    }

    private void publishCreated(Long userId, Long notificationId, NotificationType type, String link) {
        if (realtimeEvents == null || userId == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notificationId", notificationId);
        data.put("notificationType", type == null ? null : type.name());
        data.put("link", link);
        data.put("unreadCount", notifications.countByUserIdAndReadAtIsNull(userId));
        realtimeEvents.publishToUser(userId, "NOTIFICATION_CREATED", notificationId, data);
    }

    private String trim(String value, int max) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        return v.length() <= max ? v : v.substring(0, max);
    }
}
