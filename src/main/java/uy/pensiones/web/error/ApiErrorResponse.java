package uy.pensiones.web.error;

import java.time.OffsetDateTime;
import java.util.List;

public record ApiErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String requestId,
        List<FieldError> errors,
        Long retryAfterSeconds
) {
    public record FieldError(String field, String message) {}
}
