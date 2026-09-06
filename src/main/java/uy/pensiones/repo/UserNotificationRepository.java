package uy.pensiones.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.model.UserNotification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

    List<UserNotification> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    Optional<UserNotification> findByIdAndUserId(Long id, Long userId);

    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO user_notifications(user_id, type, title, message, link, dedup_key, created_at)
            VALUES (:userId, :type, :title, :message, :link, :dedupKey, :createdAt)
            ON CONFLICT (dedup_key) DO NOTHING
            """, nativeQuery = true)
    int insertDeduplicated(@Param("userId") Long userId,
                           @Param("type") String type,
                           @Param("title") String title,
                           @Param("message") String message,
                           @Param("link") String link,
                           @Param("dedupKey") String dedupKey,
                           @Param("createdAt") OffsetDateTime createdAt);

    @Transactional
    @Modifying
    @Query("""
            update UserNotification n
               set n.readAt = :readAt
             where n.user.id = :userId
               and n.readAt is null
            """)
    int markAllRead(@Param("userId") Long userId, @Param("readAt") OffsetDateTime readAt);
}
