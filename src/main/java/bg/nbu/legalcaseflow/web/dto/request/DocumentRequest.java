package bg.nbu.legalcaseflow.web.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record DocumentRequest(
        @NotBlank String title,
        String content,
        @NotNull Long clientId,
        @NotNull Long lawyerId,
        @NotNull LocalDate issueDate,
        @Min(1) int validityDays
) {
}
