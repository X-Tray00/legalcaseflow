package bg.nbu.legalcaseflow.web.dto.request;

import bg.nbu.legalcaseflow.domain.AppointmentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record AppointmentRequest(
        @NotNull Long clientId,
        @NotNull Long lawyerId,
        @NotNull LocalDateTime scheduledAt,
        AppointmentStatus status,
        @NotBlank String topic,
        String notes
) {
}

