package uy.pensiones.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uy.pensiones.enums.NotificationType;
import uy.pensiones.model.User;
import uy.pensiones.model.UserNotification;
import uy.pensiones.realtime.RealtimeEventService;
import uy.pensiones.repo.UserNotificationRepository;
import uy.pensiones.repo.UserRepository;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationServiceRealtimeTest {

    private UserNotificationRepository notifications;
    private UserRepository users;
    private RealtimeEventService realtimeEvents;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        notifications = mock(UserNotificationRepository.class);
        users = mock(UserRepository.class);
        realtimeEvents = mock(RealtimeEventService.class);
        service = new NotificationService(notifications, users, realtimeEvents);
    }

    @Test
    void publishesRealtimeEventWhenNotificationIsCreated() {
        User user = User.builder().id(9L).build();
        when(users.findById(9L)).thenReturn(Optional.of(user));
        when(notifications.save(any(UserNotification.class))).thenAnswer(invocation -> {
            UserNotification notification = invocation.getArgument(0);
            notification.setId(44L);
            return notification;
        });
        when(notifications.countByUserIdAndReadAtIsNull(9L)).thenReturn(3L);

        service.create(user, NotificationType.MODERATION_WARNING,
                "Aviso", "Revisá tu publicación", "/profile/pensions/1/edit");

        verify(realtimeEvents).publishToUser(eq(9L), eq("NOTIFICATION_CREATED"), eq(44L), argThat(data ->
                "MODERATION_WARNING".equals(data.get("notificationType"))
                        && Long.valueOf(3L).equals(data.get("unreadCount"))
        ));
    }

    @Test
    void publishesDeduplicatedNotificationOnlyWhenInsertActuallyHappened() {
        User user = User.builder().id(9L).build();
        when(notifications.insertDeduplicated(anyLong(), anyString(), anyString(), any(), any(), anyString(), any()))
                .thenReturn(1);
        when(notifications.countByUserIdAndReadAtIsNull(9L)).thenReturn(1L);

        service.createOnce(user, NotificationType.OWNER_TRIAL_EXPIRING,
                "Tu prueba finaliza pronto", "Elegí un plan", "/profile/commercial", "trial:9");

        verify(realtimeEvents).publishToUser(eq(9L), eq("NOTIFICATION_CREATED"), isNull(), anyMap());
    }
}
