package uy.pensiones.enums;

/** How the effective owner entitlements were resolved. */
public enum EntitlementSource {
    /** Monetization disabled at server level; existing behavior stays unrestricted. */
    BYPASS,
    /** Entitlements come from an active owner subscription. */
    SUBSCRIPTION,
    /** Entitlements come from an active launch-campaign benefit, without creating a fake payment/subscription. */
    LAUNCH_CAMPAIGN,
    /** Entitlements come from the currently effective FREE plan version. */
    FREE,
    /** Monetization is enabled but the commercial catalog is not safe to enforce. */
    MISCONFIGURED
}
