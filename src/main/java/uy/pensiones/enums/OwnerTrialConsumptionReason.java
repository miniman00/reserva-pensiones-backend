package uy.pensiones.enums;

/** Why the one-time owner trial eligibility can no longer be granted. */
public enum OwnerTrialConsumptionReason {
    /** The normal 90-day (configurable) owner trial was started by the first valid publication. */
    TRIAL_STARTED,
    /** The owner received the Founder launch benefit instead of the normal trial. */
    FOUNDER_GRANTED,
    /** The owner paid before using the trial; paid conversion consumes the one-time eligibility. */
    PAID_DIRECT
}
