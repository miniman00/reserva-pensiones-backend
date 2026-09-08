package uy.pensiones.enums;

/** How the effective owner entitlements were resolved. */
public enum EntitlementSource {
    /** Monetization disabled at server level; existing behavior stays unrestricted. */
    BYPASS,
    /** Entitlements come from an active owner subscription. */
    SUBSCRIPTION,
    /** Entitlements come from an active launch-campaign benefit, without creating a fake payment/subscription. */
    LAUNCH_CAMPAIGN,
    /** Founder benefit ended but its contractual grace window is still active. */
    LAUNCH_CAMPAIGN_GRACE,
    /** Trial is configured but starts only when the first valid pension is published. */
    TRIAL_PENDING,
    /** One-time owner trial is active. */
    TRIAL,
    /** Trial ended and the short grace window is active. */
    TRIAL_GRACE,
    /** The one-time free opportunity was consumed and there is no paid/founder access. */
    ACCESS_EXPIRED,
    /** Legacy rollout mode while the new trial policy is not enabled from Backoffice. */
    FREE,
    /** Monetization is enabled but the commercial catalog is not safe to enforce. */
    MISCONFIGURED
}
