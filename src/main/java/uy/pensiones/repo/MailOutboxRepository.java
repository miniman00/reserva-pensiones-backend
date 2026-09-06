package uy.pensiones.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.MailOutboxMessage;

import java.time.OffsetDateTime;
import java.util.List;

public interface MailOutboxRepository extends JpaRepository<MailOutboxMessage, Long> {

    @Modifying
    @Query(value = """
            update mail_outbox
               set lease_owner = :owner,
                   lease_until = :leaseUntil
             where id in (
                 select id
                   from mail_outbox
                  where status = 'PENDING'
                    and next_attempt_at <= :now
                    and (lease_until is null or lease_until < :now)
                  order by next_attempt_at asc, id asc
                  for update skip locked
                  limit :batchSize
             )
            """, nativeQuery = true)
    int claimBatch(@Param("owner") String owner,
                   @Param("now") OffsetDateTime now,
                   @Param("leaseUntil") OffsetDateTime leaseUntil,
                   @Param("batchSize") int batchSize);

    List<MailOutboxMessage> findByLeaseOwnerAndStatusOrderByIdAsc(String leaseOwner, MailOutboxMessage.Status status);

    long countByStatus(MailOutboxMessage.Status status);

    int deleteByStatusAndCreatedAtBefore(MailOutboxMessage.Status status, OffsetDateTime cutoff);
}
