package uy.pensiones.mail;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public interface MailService {
    record DeliveryResult(boolean sent, String message) {}

    record SubscriptionActivatedMail(
            String to,
            String displayName,
            String planName,
            String previousPlanName,
            String planDescription,
            OffsetDateTime startsAt,
            OffsetDateTime expiresAt,
            Integer periodMonths,
            BigDecimal amount,
            String currency,
            String paymentReference,
            List<String> benefits,
            String actionLink
    ) {}

    record PromotionActivatedMail(
            String to,
            String displayName,
            String pensionName,
            String promotionName,
            String promotionDescription,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            BigDecimal amount,
            String currency,
            String paymentReference,
            String actionLink
    ) {}

    void sendInvite(String to, String orgName, String link);
    void sendUserAdded(String to, String orgName);
    void sendUserRemoved(String to, String orgName);
    void sendVerifyEmail(String to, String link);
    void sendPensionInquiry(String to, String pensionName, String contactName,
                            String contactEmail, String contactPhone, String roomType,
                            String moveInDate, String message);

    void sendBackofficeSecurityNotice(String to, String displayName, String action);

    void sendPensionMaintenanceReminder(String to, String pensionName, String title, String message, String actionLink);

    void sendFounderBenefitNotice(String to, String displayName, String title, String message, String actionLink);

    void sendOwnerAccessNotice(String to, String displayName, String title, String message, String actionLink);

    void sendSubscriptionActivated(SubscriptionActivatedMail details);

    void sendPromotionActivated(PromotionActivatedMail details);

    DeliveryResult sendPaymentOperationalAlert(List<String> recipients, String severity, String category,
                                               String title, String message, String actionPath, boolean test);
}
