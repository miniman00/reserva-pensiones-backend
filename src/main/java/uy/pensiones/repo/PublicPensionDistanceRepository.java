package uy.pensiones.repo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.enums.PensionPromotionStatus;
import uy.pensiones.enums.PromotionTargetType;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionPromotion;
import uy.pensiones.pension.PensionSpecs;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Ejecuta búsquedas públicas con ranking comercial efectivo sin perder la
 * Specification dinámica usada por el marketplace.
 *
 * <p>El booleano legado {@code pensions.featured} continúa siendo compatible,
 * pero una promoción comercial vigente también eleva la pensión. La vigencia
 * comercial se resuelve dentro de PostgreSQL con un {@code EXISTS} correlacionado,
 * evitando cargar en memoria todas las promociones activas del marketplace antes
 * de cada búsqueda.</p>
 */
@Repository
@Transactional(readOnly = true)
public class PublicPensionDistanceRepository {

    private final EntityManager entityManager;
    private final int queryTimeoutMs;

    public PublicPensionDistanceRepository(
            EntityManager entityManager,
            @Value("${app.public-traffic.query-timeout-ms:5000}") int queryTimeoutMs) {
        this.entityManager = entityManager;
        this.queryTimeoutMs = Math.min(30_000, Math.max(500, queryTimeoutMs));
    }

    public Page<Pension> findPage(Specification<Pension> specification,
                                  Pageable pageable,
                                  double centerLat,
                                  double centerLng,
                                  OffsetDateTime promotionNow,
                                  Long studyCenterId) {
        List<Pension> content = findContentByDistance(
                specification,
                pageable.getOffset(),
                pageable.getPageSize(),
                centerLat,
                centerLng,
                promotionNow,
                studyCenterId
        );
        return PageableExecutionUtils.getPage(content, pageable, () -> count(specification));
    }

    public Page<Pension> findPage(Specification<Pension> specification,
                                  Pageable pageable,
                                  Sort.Order secondarySort,
                                  OffsetDateTime promotionNow,
                                  Long studyCenterId) {
        List<Pension> content = findContentBySort(
                specification,
                pageable.getOffset(),
                pageable.getPageSize(),
                secondarySort,
                promotionNow,
                studyCenterId
        );
        return PageableExecutionUtils.getPage(content, pageable, () -> count(specification));
    }

    public DistanceResult findTop(Specification<Pension> specification,
                                  int limit,
                                  double centerLat,
                                  double centerLng,
                                  OffsetDateTime promotionNow,
                                  Long studyCenterId) {
        int safeLimit = Math.max(1, limit);
        List<Pension> probed = findContentByDistance(
                specification, 0L, safeLimit + 1, centerLat, centerLng, promotionNow, studyCenterId);
        return topResult(probed, safeLimit);
    }

    public DistanceResult findTop(Specification<Pension> specification,
                                  int limit,
                                  Sort.Order secondarySort,
                                  OffsetDateTime promotionNow,
                                  Long studyCenterId) {
        int safeLimit = Math.max(1, limit);
        List<Pension> probed = findContentBySort(
                specification, 0L, safeLimit + 1, secondarySort, promotionNow, studyCenterId);
        return topResult(probed, safeLimit);
    }

    private List<Pension> findContentByDistance(Specification<Pension> specification,
                                                long offset,
                                                int limit,
                                                double centerLat,
                                                double centerLng,
                                                OffsetDateTime promotionNow,
                                                Long studyCenterId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Pension> root = query.from(Pension.class);

        applySpecification(specification, root, query, cb);

        Expression<Double> distance = PensionSpecs.distanceExpression(root, cb, centerLat, centerLng);
        Expression<Integer> promotionRank = promotionRank(root, query, cb, promotionNow, studyCenterId);
        query.multiselect(
                root.alias("pension"),
                distance.alias("distanceKm"),
                promotionRank.alias("promotionRank")
        );
        query.orderBy(
                cb.desc(promotionRank),
                cb.asc(distance),
                cb.desc(root.get("updatedAt")),
                cb.asc(root.get("id"))
        );

        return execute(query, offset, limit);
    }

    private List<Pension> findContentBySort(Specification<Pension> specification,
                                            long offset,
                                            int limit,
                                            Sort.Order secondarySort,
                                            OffsetDateTime promotionNow,
                                            Long studyCenterId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Pension> root = query.from(Pension.class);

        applySpecification(specification, root, query, cb);

        Expression<Integer> promotionRank = promotionRank(root, query, cb, promotionNow, studyCenterId);
        query.multiselect(root.alias("pension"), promotionRank.alias("promotionRank"));

        Sort.Order safeSort = secondarySort == null
                ? new Sort.Order(Sort.Direction.DESC, "updatedAt")
                : secondarySort;
        Expression<?> secondary = root.get(safeSort.getProperty());
        Order secondaryOrder = safeSort.isAscending() ? cb.asc(secondary) : cb.desc(secondary);
        query.orderBy(
                cb.desc(promotionRank),
                secondaryOrder,
                cb.asc(root.get("id"))
        );

        return execute(query, offset, limit);
    }

    private void applySpecification(Specification<Pension> specification,
                                    Root<Pension> root,
                                    CriteriaQuery<Tuple> query,
                                    CriteriaBuilder cb) {
        Predicate predicate = specification == null ? null : specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }
    }

    private Expression<Integer> promotionRank(Root<Pension> root,
                                              CriteriaQuery<Tuple> query,
                                              CriteriaBuilder cb,
                                              OffsetDateTime promotionNow,
                                              Long studyCenterId) {
        OffsetDateTime effectiveNow = promotionNow == null ? OffsetDateTime.now(ZoneOffset.UTC) : promotionNow;
        Subquery<Long> activePromotion = query.subquery(Long.class);
        Root<PensionPromotion> promotion = activePromotion.from(PensionPromotion.class);

        Predicate scope = cb.equal(promotion.<PromotionTargetType>get("targetType"), PromotionTargetType.GLOBAL);
        if (studyCenterId != null) {
            scope = cb.or(
                    scope,
                    cb.and(
                            cb.equal(promotion.<PromotionTargetType>get("targetType"), PromotionTargetType.STUDY_CENTER),
                            cb.equal(promotion.get("studyCenter").get("id"), studyCenterId)
                    )
            );
        }

        activePromotion.select(cb.literal(1L)).where(
                cb.equal(promotion.get("pension").get("id"), root.get("id")),
                cb.equal(promotion.<PensionPromotionStatus>get("status"), PensionPromotionStatus.ACTIVE),
                cb.lessThanOrEqualTo(promotion.<OffsetDateTime>get("startsAt"), effectiveNow),
                cb.or(
                        cb.isNull(promotion.<OffsetDateTime>get("endsAt")),
                        cb.greaterThan(promotion.<OffsetDateTime>get("endsAt"), effectiveNow)
                ),
                scope
        );

        Predicate promoted = cb.or(
                cb.isTrue(root.get("featured")),
                cb.exists(activePromotion)
        );
        return cb.<Integer>selectCase().when(promoted, 1).otherwise(0);
    }

    private List<Pension> execute(CriteriaQuery<Tuple> query, long offset, int limit) {
        TypedQuery<Tuple> typed = entityManager.createQuery(query);
        typed.setHint("jakarta.persistence.query.timeout", queryTimeoutMs);
        typed.setFirstResult(Math.toIntExact(offset));
        typed.setMaxResults(limit);
        return typed.getResultList().stream()
                .map(tuple -> tuple.get("pension", Pension.class))
                .toList();
    }

    private DistanceResult topResult(List<Pension> probed, int limit) {
        boolean truncated = probed.size() > limit;
        List<Pension> content = truncated ? List.copyOf(probed.subList(0, limit)) : List.copyOf(probed);
        // Cuando está truncado no ejecutamos COUNT(*): saber que existe al menos
        // un resultado adicional alcanza para la UX del mapa y evita una segunda
        // consulta potencialmente mucho más costosa que la primera.
        long visibleTotal = truncated ? (long) limit + 1L : content.size();
        return new DistanceResult(content, visibleTotal, truncated);
    }

    private long count(Specification<Pension> specification) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<Pension> root = query.from(Pension.class);

        Predicate predicate = specification == null ? null : specification.toPredicate(root, query, cb);
        if (predicate != null) {
            query.where(predicate);
        }

        boolean distinct = query.isDistinct();
        query.distinct(false);
        query.select(distinct ? cb.countDistinct(root) : cb.count(root));
        TypedQuery<Long> typed = entityManager.createQuery(query);
        typed.setHint("jakarta.persistence.query.timeout", queryTimeoutMs);
        return typed.getSingleResult();
    }

    public record DistanceResult(List<Pension> content, long totalElements, boolean truncated) {
    }
}
