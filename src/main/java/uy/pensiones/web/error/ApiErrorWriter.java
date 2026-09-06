package uy.pensiones.web.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import uy.pensiones.config.RequestCorrelationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

public final class ApiErrorWriter {

    private ApiErrorWriter() {}

    public static void write(HttpServletRequest request,
                             HttpServletResponse response,
                             ObjectMapper objectMapper,
                             HttpStatus status,
                             String code,
                             String message) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new ApiErrorResponse(
                OffsetDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                request.getRequestURI(),
                RequestCorrelationFilter.currentRequestId(request),
                List.of(),
                null
        ));
    }
}
