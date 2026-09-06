package uy.pensiones.repo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uy.pensiones.model.PensionPromotion;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface PensionPromotionRepository extends JpaRepository<PensionPromotion, Long> {

    @Query(value = """
            SELECT pp.id AS "id",
                   p.id AS "pensionId",
                   p.name AS "pensionName",
                   p.status AS "pensionStatus",
                   COALESCE(p.moderation_blocked, false) AS "pensionBlocked",
                   owner_u.id AS "ownerId",
                   owner_u.name AS "ownerName",
                   owner_u.email AS "ownerEmail",
                   COALESCE(owner_u.suspended, false) AS "ownerSuspended",
                   pp.target_type AS "targetType",
                   sc.id AS "studyCenterId",
                   sc.name AS "studyCenterName",
                   CASE WHEN pp.source = 'SUBSCRIPTION_BENEFIT' THEN 'PLAN_FEATURED'
                        WHEN pp.source = 'LAUNCH_CAMPAIGN' THEN 'FOUNDER_FEATURED'
                        ELSE COALESCE(prod.code, 'LEGACY_FEATURED') END AS "productCode",
                   CASE WHEN pp.source = 'SUBSCRIPTION_BENEFIT' THEN 'Destacado incluido en el plan'
                        WHEN pp.source = 'LAUNCH_CAMPAIGN' THEN 'Destacado Propietario Fundador'
                        ELSE COALESCE(prod.name, 'Destacado legado') END AS "productName",
                   pv.id AS "productVersionId",
                   pv.version AS "productVersion",
                   pv.price AS "catalogPrice",
                   pp.price AS "price",
                   pp.currency AS "currency",
                   pp.source AS "source",
                   pp.status AS "storedStatus",
                   CASE
                       WHEN pp.status = 'CANCELLED' THEN 'CANCELLED'
                       WHEN pp.starts_at > CURRENT_TIMESTAMP THEN 'SCHEDULED'
                       WHEN pp.ends_at IS NOT NULL AND pp.ends_at <= CURRENT_TIMESTAMP THEN 'EXPIRED'
                       ELSE 'ACTIVE'
                   END AS "effectiveStatus",
                   pp.starts_at AS "startsAt",
                   pp.ends_at AS "endsAt",
                   pp.cancelled_at AS "cancelledAt",
                   pp.cancellation_reason AS "cancellationReason",
                   bo.display_name AS "createdByDisplayName",
                   pp.created_at AS "createdAt"
              FROM pension_promotions pp
              JOIN pensions p ON p.id = pp.pension_id
              JOIN users owner_u ON owner_u.id = COALESCE(p.owner_id, p.created_by_id)
              LEFT JOIN promotion_product_versions pv ON pv.id = pp.promotion_product_version_id
              LEFT JOIN promotion_products prod ON prod.id = pv.promotion_product_id
              LEFT JOIN study_center_catalog sc ON sc.id = pp.study_center_id
              LEFT JOIN backoffice_users bo ON bo.id = pp.created_by_backoffice_user_id
             WHERE (:q = '' OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
                              OR LOWER(COALESCE(owner_u.name, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                              OR LOWER(COALESCE(owner_u.email, '')) LIKE LOWER(CONCAT('%', :q, '%')))
               AND (:pensionId IS NULL OR p.id = :pensionId)
               AND (:targetType = '' OR pp.target_type = :targetType)
               AND (:source = '' OR pp.source = :source)
               AND (:productCode = '' OR CASE
                       WHEN pp.source = 'SUBSCRIPTION_BENEFIT' THEN 'PLAN_FEATURED'
                       WHEN pp.source = 'LAUNCH_CAMPAIGN' THEN 'FOUNDER_FEATURED'
                       ELSE COALESCE(prod.code, 'LEGACY_FEATURED')
                   END = :productCode)
               AND (:studyCenterId IS NULL OR pp.study_center_id = :studyCenterId)
               AND (:effectiveStatus = '' OR
                    CASE
                       WHEN pp.status = 'CANCELLED' THEN 'CANCELLED'
                       WHEN pp.starts_at > CURRENT_TIMESTAMP THEN 'SCHEDULED'
                       WHEN pp.ends_at IS NOT NULL AND pp.ends_at <= CURRENT_TIMESTAMP THEN 'EXPIRED'
                       ELSE 'ACTIVE'
                    END = :effectiveStatus)
             ORDER BY
               CASE
                   WHEN pp.status = 'CANCELLED' THEN 4
                   WHEN pp.starts_at > CURRENT_TIMESTAMP THEN 2
                   WHEN pp.ends_at IS NOT NULL AND pp.ends_at <= CURRENT_TIMESTAMP THEN 3
                   ELSE 1
               END,
               pp.starts_at DESC,
               pp.id DESC
            """,
            countQuery = """
            SELECT COUNT(*)
              FROM pension_promotions pp
              JOIN pensions p ON p.id = pp.pension_id
              JOIN users owner_u ON owner_u.id = COALESCE(p.owner_id, p.created_by_id)
              LEFT JOIN promotion_product_versions pv ON pv.id = pp.promotion_product_version_id
              LEFT JOIN promotion_products prod ON prod.id = pv.promotion_product_id
             WHERE (:q = '' OR LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
                              OR LOWER(COALESCE(owner_u.name, '')) LIKE LOWER(CONCAT('%', :q, '%'))
                              OR LOWER(COALESCE(owner_u.email, '')) LIKE LOWER(CONCAT('%', :q, '%')))
               AND (:pensionId IS NULL OR p.id = :pensionId)
               AND (:targetType = '' OR pp.target_type = :targetType)
               AND (:source = '' OR pp.source = :source)
               AND (:productCode = '' OR CASE
                       WHEN pp.source = 'SUBSCRIPTION_BENEFIT' THEN 'PLAN_FEATURED'
                       WHEN pp.source = 'LAUNCH_CAMPAIGN' THEN 'FOUNDER_FEATURED'
                       ELSE COALESCE(prod.code, 'LEGACY_FEATURED')
                   END = :productCode)
               AND (:studyCenterId IS NULL OR pp.study_center_id = :studyCenterId)
               AND (:effectiveStatus = '' OR
                    CASE
                       WHEN pp.status = 'CANCELLED' THEN 'CANCELLED'
                       WHEN pp.starts_at > CURRENT_TIMESTAMP THEN 'SCHEDULED'
                       WHEN pp.ends_at IS NOT NULL AND pp.ends_at <= CURRENT_TIMESTAMP THEN 'EXPIRED'
                       ELSE 'ACTIVE'
                    END = :effectiveStatus)
            """, nativeQuery = true)
    Page<AdminPromotionRow> adminList(@Param("q") String q,
                                      @Param("pensionId") Long pensionId,
                                      @Param("targetType") String targetType,
                                      @Param("source") String source,
                                      @Param("productCode") String productCode,
                                      @Param("studyCenterId") Long studyCenterId,
                                      @Param("effectiveStatus") String effectiveStatus,
                                      Pageable pageable);

    @Query(value = """
            SELECT COUNT(*) AS "total",
                   COUNT(*) FILTER (
                       WHERE pp.status <> 'CANCELLED'
                         AND pp.starts_at <= CURRENT_TIMESTAMP
                         AND (pp.ends_at IS NULL OR pp.ends_at > CURRENT_TIMESTAMP)
                   ) AS "active",
                   COUNT(*) FILTER (
                       WHERE pp.status <> 'CANCELLED' AND pp.starts_at > CURRENT_TIMESTAMP
                   ) AS "scheduled",
                   COUNT(*) FILTER (
                       WHERE pp.status <> 'CANCELLED'
                         AND pp.starts_at <= CURRENT_TIMESTAMP
                         AND pp.ends_at > CURRENT_TIMESTAMP
                         AND pp.ends_at <= CURRENT_TIMESTAMP + INTERVAL '7 days'
                   ) AS "expiringWithin7Days",
                   COUNT(*) FILTER (WHERE pp.source = 'ADMIN_GRANT') AS "adminGrants",
                   COUNT(*) FILTER (WHERE pp.target_type = 'STUDY_CENTER') AS "studyCenterPromotions",
                   COUNT(*) FILTER (WHERE pp.source = 'LEGACY_COMPATIBILITY') AS "legacyPromotions"
              FROM pension_promotions pp
            """, nativeQuery = true)
    AdminPromotionSummary summary();

    @EntityGraph(attributePaths = {"pension", "productVersion", "productVersion.product", "studyCenter"})
    @Query("""
            select pp from PensionPromotion pp join pp.pension p
            where ((p.owner is not null and p.owner.id = :userId)
                   or (p.owner is null and p.createdBy.id = :userId))
              and pp.status = uy.pensiones.enums.PensionPromotionStatus.ACTIVE
              and pp.startsAt <= :now
              and (pp.endsAt is null or pp.endsAt > :now)
            order by p.name asc, pp.startsAt desc, pp.id desc
            """)
    List<PensionPromotion> findEffectiveActiveForOwner(@Param("userId") Long userId,
                                                       @Param("now") OffsetDateTime now);


    @Query(value = """
            SELECT pp.id AS "promotionId",
                   pp.pension_id AS "pensionId",
                   pp.target_type AS "targetType"
              FROM pension_promotions pp
             WHERE pp.pension_id IN (:pensionIds)
               AND pp.status = 'ACTIVE'
               AND pp.starts_at <= :now
               AND (pp.ends_at IS NULL OR pp.ends_at > :now)
               AND pp.target_type = 'GLOBAL'
             ORDER BY pp.pension_id ASC, pp.starts_at DESC, pp.id DESC
            """, nativeQuery = true)
    List<PublicPromotionExposureRow> findEffectiveGlobalExposuresForPensions(
            @Param("now") OffsetDateTime now,
            @Param("pensionIds") List<Long> pensionIds
    );

    @Query(value = """
            SELECT pp.id AS "promotionId",
                   pp.pension_id AS "pensionId",
                   pp.target_type AS "targetType"
              FROM pension_promotions pp
             WHERE pp.pension_id IN (:pensionIds)
               AND pp.status = 'ACTIVE'
               AND pp.starts_at <= :now
               AND (pp.ends_at IS NULL OR pp.ends_at > :now)
               AND (pp.target_type = 'GLOBAL'
                    OR (pp.target_type = 'STUDY_CENTER' AND pp.study_center_id = :studyCenterId))
             ORDER BY pp.pension_id ASC,
                      CASE WHEN pp.target_type = 'STUDY_CENTER' THEN 0 ELSE 1 END ASC,
                      pp.starts_at DESC, pp.id DESC
            """, nativeQuery = true)
    List<PublicPromotionExposureRow> findEffectiveExposuresForStudyCenterAndPensions(
            @Param("now") OffsetDateTime now,
            @Param("studyCenterId") Long studyCenterId,
            @Param("pensionIds") List<Long> pensionIds
    );

    @EntityGraph(attributePaths = {"pension", "productVersion", "productVersion.product", "studyCenter"})
    @Query("""
            select pp from PensionPromotion pp
             where pp.id = :promotionId
               and pp.pension.id = :pensionId
               and pp.status = uy.pensiones.enums.PensionPromotionStatus.ACTIVE
               and pp.startsAt <= :now
               and (pp.endsAt is null or pp.endsAt > :now)
            """)
    Optional<PensionPromotion> findEffectiveAttribution(@Param("promotionId") Long promotionId,
                                                        @Param("pensionId") Long pensionId,
                                                        @Param("now") OffsetDateTime now);

    @Modifying
    @Query(value = """
            INSERT INTO promotion_exposure_events
                (promotion_id, pension_id, event_type, visitor_hash, occurred_on, created_at)
            VALUES (:promotionId, :pensionId, :eventType, :visitorHash, :occurredOn, :createdAt)
            ON CONFLICT (promotion_id, event_type, visitor_hash, occurred_on) DO NOTHING
            """, nativeQuery = true)
    int insertExposureEvent(@Param("promotionId") Long promotionId,
                            @Param("pensionId") Long pensionId,
                            @Param("eventType") String eventType,
                            @Param("visitorHash") String visitorHash,
                            @Param("occurredOn") java.time.LocalDate occurredOn,
                            @Param("createdAt") OffsetDateTime createdAt);

    @Query(value = """
            SELECT pp.id AS "promotionId",
                   p.id AS "pensionId",
                   p.name AS "pensionName",
                   CASE WHEN pp.source = 'SUBSCRIPTION_BENEFIT' THEN 'PLAN_FEATURED'
                        WHEN pp.source = 'LAUNCH_CAMPAIGN' THEN 'FOUNDER_FEATURED'
                        ELSE COALESCE(prod.code, 'LEGACY_FEATURED') END AS "productCode",
                   CASE WHEN pp.source = 'SUBSCRIPTION_BENEFIT' THEN 'Destacado incluido en el plan'
                        WHEN pp.source = 'LAUNCH_CAMPAIGN' THEN 'Destacado Propietario Fundador'
                        ELSE COALESCE(prod.name, 'Destacado legado') END AS "productName",
                   pp.target_type AS "targetType",
                   sc.name AS "studyCenterName",
                   pp.starts_at AS "startsAt",
                   pp.ends_at AS "endsAt",
                   CASE
                       WHEN pp.status = 'CANCELLED' THEN 'CANCELLED'
                       WHEN pp.starts_at > CURRENT_TIMESTAMP THEN 'SCHEDULED'
                       WHEN pp.ends_at IS NOT NULL AND pp.ends_at <= CURRENT_TIMESTAMP THEN 'EXPIRED'
                       ELSE 'ACTIVE'
                   END AS "effectiveStatus",
                   (SELECT COUNT(*) FROM promotion_exposure_events e
                     WHERE e.promotion_id = pp.id AND e.event_type = 'IMPRESSION') AS "impressions",
                   (SELECT COUNT(*) FROM promotion_exposure_events e
                     WHERE e.promotion_id = pp.id AND e.event_type = 'CLICK') AS "clicks",
                   COUNT(i.id) AS "inquiries",
                   COUNT(i.id) FILTER (WHERE i.converted_at IS NOT NULL) AS "conversions"
              FROM pension_promotions pp
              JOIN pensions p ON p.id = pp.pension_id
              LEFT JOIN promotion_product_versions pv ON pv.id = pp.promotion_product_version_id
              LEFT JOIN promotion_products prod ON prod.id = pv.promotion_product_id
              LEFT JOIN study_center_catalog sc ON sc.id = pp.study_center_id
              LEFT JOIN pension_inquiries i ON i.attributed_promotion_id = pp.id
             WHERE COALESCE(p.owner_id, p.created_by_id) = :userId
             GROUP BY pp.id, p.id, p.name, prod.code, prod.name, pp.target_type, sc.name,
                      pp.starts_at, pp.ends_at, pp.status
             ORDER BY pp.starts_at DESC, pp.id DESC
             LIMIT 20
            """, nativeQuery = true)
    List<OwnerPromotionPerformanceRow> ownerPerformance(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pp from PensionPromotion pp where pp.id = :id")
    Optional<PensionPromotion> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select pp from PensionPromotion pp
              join fetch pp.pension p
              join fetch p.createdBy
              left join fetch p.owner
              left join fetch pp.productVersion pv
              left join fetch pv.product
              left join fetch pp.studyCenter
              left join fetch pp.createdByBackoffice
             where pp.id = :id
            """)
    Optional<PensionPromotion> findAdminDetailById(@Param("id") Long id);

    @Query(value = """
            SELECT COUNT(*)
              FROM pension_promotions pp
             WHERE pp.pension_id = :pensionId
               AND pp.status <> 'CANCELLED'
               AND pp.target_type = :targetType
               AND ((:studyCenterId IS NULL AND pp.study_center_id IS NULL) OR pp.study_center_id = :studyCenterId)
               AND pp.starts_at < :newEnd
               AND (pp.ends_at IS NULL OR pp.ends_at > :newStart)
            """, nativeQuery = true)
    long countOverlapping(@Param("pensionId") Long pensionId,
                          @Param("targetType") String targetType,
                          @Param("studyCenterId") Long studyCenterId,
                          @Param("newStart") OffsetDateTime newStart,
                          @Param("newEnd") OffsetDateTime newEnd);

    @Modifying
    @Query("""
            update PensionPromotion pp
               set pp.status = uy.pensiones.enums.PensionPromotionStatus.CANCELLED,
                   pp.cancelledAt = :now,
                   pp.cancellationReason = :reason,
                   pp.updatedAt = :now
             where pp.pension.id = :pensionId
               and pp.source = :source
               and pp.status = uy.pensiones.enums.PensionPromotionStatus.ACTIVE
               and pp.startsAt <= :now
               and (pp.endsAt is null or pp.endsAt > :now)
            """)
    int cancelEffectiveForPensionBySource(@Param("pensionId") Long pensionId,
                                          @Param("source") uy.pensiones.enums.PensionPromotionSource source,
                                          @Param("now") OffsetDateTime now,
                                          @Param("reason") String reason);

    @Query(value = """
            SELECT COUNT(*)
              FROM pension_promotions pp
              JOIN pensions p ON p.id = pp.pension_id
             WHERE COALESCE(p.owner_id, p.created_by_id) = :userId
               AND pp.source = :source
               AND pp.status = 'ACTIVE'
               AND pp.starts_at <= :now
               AND (pp.ends_at IS NULL OR pp.ends_at > :now)
            """, nativeQuery = true)
    long countEffectiveActiveForOwnerBySource(@Param("userId") Long userId,
                                              @Param("source") String source,
                                              @Param("now") OffsetDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select pp from PensionPromotion pp
              join pp.pension p
             where pp.id = :promotionId
               and ((p.owner is not null and p.owner.id = :userId)
                    or (p.owner is null and p.createdBy.id = :userId))
               and pp.source = :source
               and pp.status = uy.pensiones.enums.PensionPromotionStatus.ACTIVE
               and pp.startsAt <= :now
               and (pp.endsAt is null or pp.endsAt > :now)
            """)
    Optional<PensionPromotion> findFounderPromotionForOwnerForUpdate(@Param("promotionId") Long promotionId,
                                                                      @Param("userId") Long userId,
                                                                      @Param("source") uy.pensiones.enums.PensionPromotionSource source,
                                                                      @Param("now") OffsetDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PensionPromotion pp
               set pp.status = uy.pensiones.enums.PensionPromotionStatus.CANCELLED,
                   pp.cancelledAt = :now,
                   pp.cancellationReason = :reason,
                   pp.updatedAt = :now
             where ((pp.pension.owner is not null and pp.pension.owner.id = :userId)
                    or (pp.pension.owner is null and pp.pension.createdBy.id = :userId))
               and pp.source = :source
               and pp.status = uy.pensiones.enums.PensionPromotionStatus.ACTIVE
               and pp.startsAt <= :now
               and (pp.endsAt is null or pp.endsAt > :now)
            """)
    int cancelEffectiveForOwnerBySource(@Param("userId") Long userId,
                                        @Param("source") uy.pensiones.enums.PensionPromotionSource source,
                                        @Param("now") OffsetDateTime now,
                                        @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PensionPromotion pp
               set pp.endsAt = :newEnd,
                   pp.updatedAt = :now
             where ((pp.pension.owner is not null and pp.pension.owner.id = :userId)
                    or (pp.pension.owner is null and pp.pension.createdBy.id = :userId))
               and pp.source = :source
               and pp.status = uy.pensiones.enums.PensionPromotionStatus.ACTIVE
               and pp.endsAt = :oldEnd
            """)
    int extendFounderPromotionsForOwner(@Param("userId") Long userId,
                                        @Param("source") uy.pensiones.enums.PensionPromotionSource source,
                                        @Param("oldEnd") OffsetDateTime oldEnd,
                                        @Param("newEnd") OffsetDateTime newEnd,
                                        @Param("now") OffsetDateTime now);

    interface PublicPromotionExposureRow {
        Long getPromotionId();
        Long getPensionId();
        String getTargetType();
    }

    interface OwnerPromotionPerformanceRow {
        Long getPromotionId();
        Long getPensionId();
        String getPensionName();
        String getProductCode();
        String getProductName();
        String getTargetType();
        String getStudyCenterName();
        java.time.Instant getStartsAt();
        java.time.Instant getEndsAt();
        String getEffectiveStatus();
        Long getImpressions();
        Long getClicks();
        Long getInquiries();
        Long getConversions();
    }

    interface AdminPromotionRow {
        Long getId();
        Long getPensionId();
        String getPensionName();
        String getPensionStatus();
        Boolean getPensionBlocked();
        Long getOwnerId();
        String getOwnerName();
        String getOwnerEmail();
        Boolean getOwnerSuspended();
        String getTargetType();
        Long getStudyCenterId();
        String getStudyCenterName();
        String getProductCode();
        String getProductName();
        Long getProductVersionId();
        Integer getProductVersion();
        BigDecimal getCatalogPrice();
        BigDecimal getPrice();
        String getCurrency();
        String getSource();
        String getStoredStatus();
        String getEffectiveStatus();
        OffsetDateTime getStartsAt();
        OffsetDateTime getEndsAt();
        OffsetDateTime getCancelledAt();
        String getCancellationReason();
        String getCreatedByDisplayName();
        OffsetDateTime getCreatedAt();
    }

    interface AdminPromotionSummary {
        Long getTotal();
        Long getActive();
        Long getScheduled();
        Long getExpiringWithin7Days();
        Long getAdminGrants();
        Long getStudyCenterPromotions();
        Long getLegacyPromotions();
    }
}
