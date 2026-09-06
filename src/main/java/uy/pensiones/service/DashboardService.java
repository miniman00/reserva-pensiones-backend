package uy.pensiones.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.enums.InquiryStatus;
import uy.pensiones.model.Pension;
import uy.pensiones.repo.PensionFavoriteRepository;
import uy.pensiones.repo.PensionInquiryRepository;
import uy.pensiones.repo.PensionViewRepository;
import uy.pensiones.security.Authz;
import uy.pensiones.web.dto.DashboardDTO;
import uy.pensiones.web.dto.PensionInquiryDTO;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private static final int METRICS_DAYS = 30;

    private final PensionService pensionService;
    private final PensionFavoriteRepository favorites;
    private final PensionInquiryRepository inquiries;
    private final PensionViewRepository views;
    private final Authz authz;

    public DashboardService(PensionService pensionService,
                            PensionFavoriteRepository favorites,
                            PensionInquiryRepository inquiries,
                            PensionViewRepository views,
                            Authz authz) {
        this.pensionService = pensionService;
        this.favorites = favorites;
        this.inquiries = inquiries;
        this.views = views;
        this.authz = authz;
    }

    @Transactional(readOnly = true)
    public DashboardDTO build(Long userId) {
        List<Pension> accessible = pensionService.myPensions(userId);
        List<Pension> owned = new ArrayList<>();
        List<Pension> collaborations = new ArrayList<>();

        for (Pension pension : accessible) {
            if (authz.isOwner(userId, pension.getId())) owned.add(pension);
            else collaborations.add(pension);
        }

        List<Long> ownedIds = owned.stream().map(Pension::getId).toList();
        Map<Long, Long> favoriteCounts = new HashMap<>();
        Map<Long, Long> favorite30Counts = new HashMap<>();
        Map<Long, Long> inquiry30Counts = new HashMap<>();
        Map<Long, Long> viewCounts = new HashMap<>();
        Map<Long, Long> view30Counts = new HashMap<>();
        Map<Long, StatusCounts> inquiryCounts = new HashMap<>();

        LocalDate fromDate30 = LocalDate.now(ZoneOffset.UTC).minusDays(METRICS_DAYS - 1L);
        OffsetDateTime since30 = fromDate30.atStartOfDay().atOffset(ZoneOffset.UTC);

        if (!ownedIds.isEmpty()) {
            favorites.countByPensionIds(ownedIds)
                    .forEach(row -> favoriteCounts.put(row.getPensionId(), row.getTotal()));
            favorites.countByPensionIdsSince(ownedIds, since30)
                    .forEach(row -> favorite30Counts.put(row.getPensionId(), row.getTotal()));

            inquiries.countByPensionIdsAndStatus(ownedIds).forEach(row -> {
                StatusCounts counts = inquiryCounts.computeIfAbsent(row.getPensionId(), ignored -> new StatusCounts());
                counts.add(row.getStatus(), row.getTotal());
            });
            inquiries.countByPensionIdsSince(ownedIds, since30)
                    .forEach(row -> inquiry30Counts.put(row.getPensionId(), row.getTotal()));

            views.countByPensionIds(ownedIds)
                    .forEach(row -> viewCounts.put(row.getPensionId(), row.getTotal()));
            views.countByPensionIdsSince(ownedIds, fromDate30)
                    .forEach(row -> view30Counts.put(row.getPensionId(), row.getTotal()));
        }

        long favoritesReceived = favoriteCounts.values().stream().mapToLong(Long::longValue).sum();
        long inquiriesNew = inquiryCounts.values().stream().mapToLong(c -> c.newCount).sum();
        long inquiriesContacted = inquiryCounts.values().stream().mapToLong(c -> c.contactedCount).sum();
        long inquiriesClosed = inquiryCounts.values().stream().mapToLong(c -> c.closedCount).sum();
        long inquiriesTotal = inquiriesNew + inquiriesContacted + inquiriesClosed;
        long viewsTotal = viewCounts.values().stream().mapToLong(Long::longValue).sum();
        long viewsLast30Days = view30Counts.values().stream().mapToLong(Long::longValue).sum();
        long favoritesLast30Days = favorite30Counts.values().stream().mapToLong(Long::longValue).sum();
        long inquiriesLast30Days = inquiry30Counts.values().stream().mapToLong(Long::longValue).sum();

        int totalCapacity = owned.stream().mapToInt(this::capacity).sum();
        int totalAvailable = owned.stream().mapToInt(this::available).sum();

        List<DashboardDTO.PensionActivity> ownedActivity = owned.stream()
                .map(p -> {
                    StatusCounts counts = inquiryCounts.getOrDefault(p.getId(), new StatusCounts());
                    long views30 = view30Counts.getOrDefault(p.getId(), 0L);
                    long inquiries30 = inquiry30Counts.getOrDefault(p.getId(), 0L);
                    return new DashboardDTO.PensionActivity(
                            p.getId(), p.getName(), p.getCity(), p.getState(),
                            safe(p.getCapacitySimple()), safe(p.getCapacityMatrimonial()),
                            safe(p.getAvailableSimple()), safe(p.getAvailableMatrimonial()),
                            favoriteCounts.getOrDefault(p.getId(), 0L),
                            counts.total(), counts.newCount, counts.contactedCount, counts.closedCount,
                            viewCounts.getOrDefault(p.getId(), 0L),
                            views30,
                            favorite30Counts.getOrDefault(p.getId(), 0L),
                            inquiries30,
                            conversion(inquiries30, views30),
                            p.getAvailabilityUpdatedAt()
                    );
                })
                .toList();

        List<DashboardDTO.CollaborationActivity> collaborationActivity = collaborations.stream()
                .map(p -> new DashboardDTO.CollaborationActivity(
                        p.getId(), p.getName(), p.getCity(), p.getState(),
                        safe(p.getCapacitySimple()), safe(p.getCapacityMatrimonial()),
                        safe(p.getAvailableSimple()), safe(p.getAvailableMatrimonial()),
                        authz.canUpdateAvailability(userId, p.getId()),
                        p.getAvailabilityUpdatedAt()
                ))
                .toList();

        List<PensionInquiryDTO> recent = ownedIds.isEmpty()
                ? List.of()
                : inquiries.findTop5ByPensionIdInOrderByCreatedAtDesc(ownedIds).stream()
                .map(PensionInquiryDTO::of)
                .toList();

        return new DashboardDTO(
                new DashboardDTO.AccountSummary(
                        favorites.countByUserId(userId),
                        inquiries.countByRequesterId(userId)
                ),
                new DashboardDTO.OwnerSummary(
                        owned.size(), totalCapacity, totalAvailable,
                        favoritesReceived, inquiriesTotal, inquiriesNew, inquiriesContacted, inquiriesClosed,
                        viewsTotal, viewsLast30Days, favoritesLast30Days, inquiriesLast30Days,
                        conversion(inquiriesLast30Days, viewsLast30Days)
                ),
                ownedActivity,
                collaborationActivity,
                recent
        );
    }

    private double conversion(long inquiriesCount, long viewsCount) {
        if (viewsCount <= 0) return 0d;
        return Math.round((inquiriesCount * 10000d) / viewsCount) / 100d;
    }

    private int capacity(Pension p) {
        return safe(p.getCapacitySimple()) + safe(p.getCapacityMatrimonial());
    }

    private int available(Pension p) {
        return safe(p.getAvailableSimple()) + safe(p.getAvailableMatrimonial());
    }

    private int safe(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static class StatusCounts {
        long newCount;
        long contactedCount;
        long closedCount;

        void add(InquiryStatus status, long value) {
            if (status == null) return;
            switch (status) {
                case NEW -> newCount += value;
                case CONTACTED -> contactedCount += value;
                case CLOSED -> closedCount += value;
            }
        }

        long total() {
            return newCount + contactedCount + closedCount;
        }
    }
}
