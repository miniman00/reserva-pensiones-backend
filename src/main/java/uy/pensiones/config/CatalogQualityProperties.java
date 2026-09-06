package uy.pensiones.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.catalog-quality")
public class CatalogQualityProperties {

    private int availabilityReminderDays = 10;
    private int availabilityStaleDays = 20;
    private int maxPublicAvailabilityAgeDays = 30;
    private int draftReminderDays = 7;

    public int getAvailabilityReminderDays() {
        return Math.max(1, availabilityReminderDays);
    }

    public void setAvailabilityReminderDays(int availabilityReminderDays) {
        this.availabilityReminderDays = availabilityReminderDays;
    }

    public int getAvailabilityStaleDays() {
        return Math.max(getAvailabilityReminderDays(), availabilityStaleDays);
    }

    public void setAvailabilityStaleDays(int availabilityStaleDays) {
        this.availabilityStaleDays = availabilityStaleDays;
    }

    public int getMaxPublicAvailabilityAgeDays() {
        return Math.max(getAvailabilityStaleDays(), maxPublicAvailabilityAgeDays);
    }

    public void setMaxPublicAvailabilityAgeDays(int maxPublicAvailabilityAgeDays) {
        this.maxPublicAvailabilityAgeDays = maxPublicAvailabilityAgeDays;
    }

    public int getDraftReminderDays() {
        return Math.max(1, draftReminderDays);
    }

    public void setDraftReminderDays(int draftReminderDays) {
        this.draftReminderDays = draftReminderDays;
    }
}
