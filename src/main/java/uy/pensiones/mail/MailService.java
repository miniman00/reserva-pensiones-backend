package uy.pensiones.mail;

public interface MailService {
    record DeliveryResult(boolean sent, String message) {}

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

    DeliveryResult sendPaymentOperationalAlert(java.util.List<String> recipients, String severity, String category,
                                               String title, String message, String actionPath, boolean test);
}
