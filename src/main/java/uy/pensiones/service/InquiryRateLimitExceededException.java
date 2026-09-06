package uy.pensiones.service;

public class InquiryRateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public InquiryRateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
