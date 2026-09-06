package uy.pensiones.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.pensiones.repo.AdminDashboardQueryRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class AdminDashboardService {

    private final AdminDashboardQueryRepository dashboard;

    public AdminDashboardService(AdminDashboardQueryRepository dashboard) {
        this.dashboard = dashboard;
    }

    @Transactional(readOnly = true)
    public AdminDashboardDTO get() {
        AdminDashboardQueryRepository.AdminDashboardMetrics m = dashboard.metrics();
        return new AdminDashboardDTO(
                new MarketplaceMetrics(safe(m == null ? null : m.getMarketplaceUsers()),
                        safe(m == null ? null : m.getOwnerRoleUsers()),
                        safe(m == null ? null : m.getPensionOwners()),
                        safe(m == null ? null : m.getSuspendedUsers())),
                new PensionMetrics(safe(m == null ? null : m.getPensions()),
                        safe(m == null ? null : m.getDraftPensions()),
                        safe(m == null ? null : m.getPublishedPensions()),
                        safe(m == null ? null : m.getPausedPensions()),
                        safe(m == null ? null : m.getBlockedPensions()),
                        safe(m == null ? null : m.getFeaturedPensions()),
                        safe(m == null ? null : m.getVisiblePensions()),
                        safe(m == null ? null : m.getVisibleAvailablePensions())),
                new ModerationMetrics(safe(m == null ? null : m.getReports()),
                        safe(m == null ? null : m.getNewReports()),
                        safe(m == null ? null : m.getUnderReviewReports())),
                new EngagementMetrics(safe(m == null ? null : m.getInquiries()),
                        safe(m == null ? null : m.getNewInquiries()),
                        safe(m == null ? null : m.getViews()),
                        safe(m == null ? null : m.getFavorites())),
                new StudyCenterMetrics(safe(m == null ? null : m.getStudyCenters()),
                        safe(m == null ? null : m.getActiveStudyCenters()),
                        safe(m == null ? null : m.getVerifiedStudyCenters()),
                        safe(m == null ? null : m.getStudyCentersMissingCoordinates())),
                new CommercialMetrics(safe(m == null ? null : m.getActiveSubscriptions()),
                        safe(m == null ? null : m.getAdminGrantedSubscriptions()),
                        safe(m == null ? null : m.getActivePromotions()),
                        safe(m == null ? null : m.getAdminGrantedPromotions())),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    private long safe(Long value) {
        return value == null ? 0L : value;
    }

    public record AdminDashboardDTO(
            MarketplaceMetrics marketplace,
            PensionMetrics pensions,
            ModerationMetrics moderation,
            EngagementMetrics engagement,
            StudyCenterMetrics studyCenters,
            CommercialMetrics commercial,
            OffsetDateTime generatedAt
    ) {}

    public record MarketplaceMetrics(long users, long ownerRoleUsers, long pensionOwners, long suspended) {}

    public record PensionMetrics(
            long total,
            long draft,
            long published,
            long paused,
            long blocked,
            long featured,
            long visible,
            long visibleWithAvailability
    ) {}

    public record ModerationMetrics(long reports, long newReports, long underReview) {}

    public record EngagementMetrics(long inquiries, long newInquiries, long views, long favorites) {}

    public record StudyCenterMetrics(long total, long active, long verified, long missingCoordinates) {}

    public record CommercialMetrics(long activeSubscriptions, long adminGrantedSubscriptions,
                                    long activePromotions, long adminGrantedPromotions) {}
}
