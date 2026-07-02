package bg.nbu.legalcaseflow.web.dto.response;

import java.time.LocalDate;

public record DocumentResponse(
        Long id,
        String title,
        String content,
        Long clientId,
        String clientName,
        Long lawyerId,
        String lawyerName,
        LocalDate issueDate,
        int validityDays
) {
}
