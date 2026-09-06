package uy.pensiones.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uy.pensiones.enums.InquiryClosureReason;
import uy.pensiones.enums.InquiryStatus;
import uy.pensiones.model.Pension;
import uy.pensiones.model.PensionInquiry;
import uy.pensiones.model.User;
import uy.pensiones.repo.PensionInquiryRepository;
import uy.pensiones.security.Authz;
import uy.pensiones.web.dto.OwnerInquiryHistoryResponse;
import uy.pensiones.web.dto.PensionInquiryDTO;

import jakarta.persistence.criteria.Predicate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class OwnerInquiryHistoryService {

    private static final ZoneId MARKET_ZONE = ZoneId.of("America/Montevideo");
    private static final int MAX_PAGE_SIZE = 50;

    private final PensionInquiryRepository inquiries;
    private final PensionService pensions;
    private final Authz authz;
    private final OwnerEntitlementService entitlements;
    private final PensionInquiryConversationService conversations;

    public OwnerInquiryHistoryService(PensionInquiryRepository inquiries,
                                      PensionService pensions,
                                      Authz authz,
                                      OwnerEntitlementService entitlements,
                                      PensionInquiryConversationService conversations) {
        this.inquiries = inquiries;
        this.pensions = pensions;
        this.authz = authz;
        this.entitlements = entitlements;
        this.conversations = conversations;
    }

    @Transactional(readOnly = true)
    public OwnerInquiryHistoryResponse history(User owner,
                                               int page,
                                               int size,
                                               Long pensionId,
                                               InquiryStatus status,
                                               InquiryClosureReason closureReason,
                                               LocalDate from,
                                               LocalDate to,
                                               String search) {
        if (owner == null || owner.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No autenticado");
        }
        entitlements.requireInquiryHistory(owner.getId());

        List<Pension> owned = pensions.myPensions(owner.getId()).stream()
                .filter(pension -> authz.isOwner(owner.getId(), pension.getId()))
                .toList();
        List<Long> ownedIds = owned.stream().map(Pension::getId).toList();

        if (pensionId != null && !ownedIds.contains(pensionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pensión no encontrada");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha desde no puede ser posterior a la fecha hasta");
        }

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        Specification<PensionInquiry> spec = specification(ownedIds, pensionId, status, closureReason, from, to, search);

        var result = inquiries.findAll(spec, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        List<Long> ids = result.getContent().stream().map(PensionInquiry::getId).toList();
        var unread = conversations.unreadCounts(ids, owner);
        List<PensionInquiryDTO> items = result.getContent().stream()
                .map(item -> PensionInquiryDTO.of(item, unread.getOrDefault(item.getId(), 0L)))
                .toList();

        long total = inquiries.count(spec);
        long newCount = inquiries.count(spec.and(statusIs(InquiryStatus.NEW)));
        long contactedCount = inquiries.count(spec.and(statusIs(InquiryStatus.CONTACTED)));
        long closedCount = inquiries.count(spec.and(statusIs(InquiryStatus.CLOSED)));
        long convertedCount = inquiries.count(spec.and((root, query, cb) -> cb.isNotNull(root.get("convertedAt"))));
        long noAvailability = inquiries.count(spec.and(closureIs(InquiryClosureReason.NO_AVAILABILITY)));
        long noInterest = inquiries.count(spec.and(closureIs(InquiryClosureReason.NO_INTEREST)));
        long other = inquiries.count(spec.and(closureIs(InquiryClosureReason.OTHER)));
        double conversionRate = total <= 0 ? 0D : round2((convertedCount * 100D) / total);

        var summary = new OwnerInquiryHistoryResponse.Summary(
                total, newCount, contactedCount, closedCount, convertedCount,
                noAvailability, noInterest, other, conversionRate
        );
        var options = owned.stream()
                .sorted((left, right) -> safeName(left).compareToIgnoreCase(safeName(right)))
                .map(pension -> new OwnerInquiryHistoryResponse.PensionOption(pension.getId(), pension.getName()))
                .toList();

        return new OwnerInquiryHistoryResponse(
                items, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                from, to, summary, options
        );
    }

    private Specification<PensionInquiry> specification(List<Long> ownedIds,
                                                         Long pensionId,
                                                         InquiryStatus status,
                                                         InquiryClosureReason closureReason,
                                                         LocalDate from,
                                                         LocalDate to,
                                                         String search) {
        return (root, query, cb) -> {
            if (ownedIds.isEmpty()) return cb.disjunction();
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("pension").get("id").in(ownedIds));
            if (pensionId != null) predicates.add(cb.equal(root.get("pension").get("id"), pensionId));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (closureReason != null) predicates.add(cb.equal(root.get("closureReason"), closureReason));
            if (from != null) {
                OffsetDateTime start = from.atStartOfDay(MARKET_ZONE).toOffsetDateTime();
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
            }
            if (to != null) {
                OffsetDateTime endExclusive = to.plusDays(1).atTime(LocalTime.MIN).atZone(MARKET_ZONE).toOffsetDateTime();
                predicates.add(cb.lessThan(root.get("createdAt"), endExclusive));
            }
            String term = clean(search);
            if (term != null) {
                String pattern = "%" + escapeLike(term.toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("contactName")), pattern, '\\'),
                        cb.like(cb.lower(root.get("contactEmail")), pattern, '\\'),
                        cb.like(cb.lower(root.get("contactPhone")), pattern, '\\'),
                        cb.like(cb.lower(root.get("message")), pattern, '\\'),
                        cb.like(cb.lower(root.get("pension").get("name")), pattern, '\\')
                ));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Specification<PensionInquiry> statusIs(InquiryStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    private Specification<PensionInquiry> closureIs(InquiryClosureReason reason) {
        return (root, query, cb) -> cb.equal(root.get("closureReason"), reason);
    }

    private String clean(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result.substring(0, Math.min(120, result.length()));
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String safeName(Pension pension) {
        return pension.getName() == null ? "" : pension.getName();
    }

    private double round2(double value) {
        return Math.round(value * 100D) / 100D;
    }
}
