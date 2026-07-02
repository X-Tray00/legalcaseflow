package bg.nbu.legalcaseflow.web.dto.response;

import bg.nbu.legalcaseflow.domain.AppointmentStatus;

import java.time.LocalDateTime;

public record AppointmentResponse(
        Long id,
        Long clientId,
        String clientName,
        Long lawyerId,
        String lawyerName,
        LocalDateTime scheduledAt,
        AppointmentStatus status,
        String topic,
        String notes
) {
}

