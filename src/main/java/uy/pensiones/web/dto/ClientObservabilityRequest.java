package uy.pensiones.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ClientObservabilityRequest(
        @NotEmpty @Size(max = 12) List<@Valid Event> events
) {
    public record Event(
            @NotNull Type type,
            @Size(max = 120) String route,
            Double value,
            @Size(max = 12) String rating
    ) {}

    public enum Type {
        LCP,
        CLS,
        FCP,
        TTFB,
        LONG_TASK,
        JS_ERROR,
        UNHANDLED_REJECTION,
        FATAL_RENDER
    }
}
