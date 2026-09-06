package uy.pensiones.service;

/** Machine-readable commercial limit violation used by the public API contract. */
public class EntitlementLimitExceededException extends RuntimeException {
    private final String limitCode;
    private final Integer limit;
    private final long currentUsage;

    public EntitlementLimitExceededException(String limitCode, Integer limit, long currentUsage, String message) {
        super(message);
        this.limitCode = limitCode;
        this.limit = limit;
        this.currentUsage = currentUsage;
    }

    public String getLimitCode() { return limitCode; }
    public Integer getLimit() { return limit; }
    public long getCurrentUsage() { return currentUsage; }
}
