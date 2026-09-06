package uy.pensiones.web.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import uy.pensiones.config.RequestCorrelationFilter;
import uy.pensiones.service.InquiryRateLimitExceededException;
import uy.pensiones.service.EntitlementLimitExceededException;
import uy.pensiones.service.MonetizationConfigurationException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@RestControllerAdvice(basePackages = "uy.pensiones.web")
public class GlobalApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiErrorResponse.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiErrorResponse.FieldError(
                        error.getField(),
                        error.getDefaultMessage() == null ? "Dato inválido" : error.getDefaultMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Revisa los datos ingresados.", request, errors, null, null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> constraintValidation(ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiErrorResponse.FieldError> errors = ex.getConstraintViolations().stream()
                .map(error -> new ApiErrorResponse.FieldError(
                        error.getPropertyPath().toString(), error.getMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Revisa los datos ingresados.", request, errors, null, null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    ResponseEntity<ApiErrorResponse> badRequest(Exception ex, HttpServletRequest request) {
        String message = ex instanceof IllegalArgumentException && ex.getMessage() != null
                ? ex.getMessage()
                : "La solicitud contiene datos con un formato inválido.";
        return response(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, request, List.of(), null, null);
    }

    @ExceptionHandler({jakarta.persistence.QueryTimeoutException.class, org.springframework.dao.QueryTimeoutException.class})
    ResponseEntity<ApiErrorResponse> queryTimeout(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "QUERY_TIMEOUT",
                "La consulta tardó demasiado. Reduce la cantidad de filtros o el área de búsqueda e intenta nuevamente.",
                request, List.of(), null, null);
    }

    @ExceptionHandler(InquiryRateLimitExceededException.class)
    ResponseEntity<ApiErrorResponse> rateLimit(InquiryRateLimitExceededException ex, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()));
        return response(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", ex.getMessage(), request,
                List.of(), ex.getRetryAfterSeconds(), headers);
    }

    @ExceptionHandler(EntitlementLimitExceededException.class)
    ResponseEntity<ApiErrorResponse> entitlementLimit(EntitlementLimitExceededException ex, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "ENTITLEMENT_" + ex.getLimitCode(), ex.getMessage(), request,
                List.of(new ApiErrorResponse.FieldError(
                        ex.getLimitCode(),
                        "Límite: " + ex.getLimit() + " · uso actual: " + ex.getCurrentUsage()
                )), null, null);
    }

    @ExceptionHandler(MonetizationConfigurationException.class)
    ResponseEntity<ApiErrorResponse> monetizationConfiguration(MonetizationConfigurationException ex, HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "MONETIZATION_" + ex.getConfigurationCode(),
                ex.getMessage(), request, List.of(), null, null);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> responseStatus(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = ex.getReason() == null || ex.getReason().isBlank()
                ? defaultMessage(status)
                : ex.getReason();
        return response(status, codeFor(status), message, request, List.of(), null, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> accessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "No tienes permisos para realizar esta operación.",
                request, List.of(), null, null);
    }

    @ExceptionHandler({NoSuchElementException.class, NoResourceFoundException.class})
    ResponseEntity<ApiErrorResponse> notFound(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "El recurso solicitado no existe.",
                request, List.of(), null, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                       HttpServletRequest request) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "El método HTTP no está permitido para este recurso.", request, List.of(), null, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> conflict(DataIntegrityViolationException ex, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "DATA_CONFLICT",
                "La operación entra en conflicto con información existente.", request, List.of(), null, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiErrorResponse> uploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "El archivo enviado supera el tamaño máximo permitido.", request, List.of(), null, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> unexpected(Exception ex, HttpServletRequest request) {
        String requestId = RequestCorrelationFilter.currentRequestId(request);
        log.error("Error no controlado. requestId={} method={} path={}", requestId,
                request.getMethod(), request.getRequestURI(), ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Ocurrió un error inesperado. Intenta nuevamente más tarde.", request, List.of(), null, null);
    }

    private ResponseEntity<ApiErrorResponse> response(HttpStatus status,
                                                       String code,
                                                       String message,
                                                       HttpServletRequest request,
                                                       List<ApiErrorResponse.FieldError> errors,
                                                       Long retryAfterSeconds,
                                                       HttpHeaders headers) {
        ApiErrorResponse body = new ApiErrorResponse(
                OffsetDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                request.getRequestURI(),
                RequestCorrelationFilter.currentRequestId(request),
                errors,
                retryAfterSeconds
        );
        return headers == null
                ? ResponseEntity.status(status).body(body)
                : new ResponseEntity<>(body, headers, status);
    }

    private String codeFor(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "BAD_REQUEST";
            case UNAUTHORIZED -> "UNAUTHORIZED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case TOO_MANY_REQUESTS -> "RATE_LIMITED";
            default -> "HTTP_" + status.value();
        };
    }

    private String defaultMessage(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "Debes iniciar sesión para continuar.";
            case FORBIDDEN -> "No tienes permisos para realizar esta operación.";
            case NOT_FOUND -> "El recurso solicitado no existe.";
            default -> status.is5xxServerError()
                    ? "El servicio no pudo completar la operación."
                    : "No se pudo completar la solicitud.";
        };
    }
}
