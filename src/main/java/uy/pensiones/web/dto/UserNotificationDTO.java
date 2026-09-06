package uy.pensiones.web.dto;

import uy.pensiones.enums.NotificationType;
import uy.pensiones.model.UserNotification;

import java.time.OffsetDateTime;

public record UserNotificationDTO(
        Long id,
        NotificationType type,
        String title,
        String message,
        String link,
        boolean read,
        OffsetDateTime createdAt
) {
    public static UserNotificationDTO of(UserNotification notification) {
        return new UserNotificationDTO(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getLink(),
                notification.getReadAt() != null,
                notification.getCreatedAt()
        );
    }
}
