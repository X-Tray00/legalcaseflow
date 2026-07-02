package bg.nbu.legalcaseflow.web.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LegalServiceRequest(
        @NotNull LocalDate date,
        @NotNull Long lawyerId,
        @NotNull Long clientId,
        @NotNull Long caseTypeId,
        String description,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal fee,
        boolean paid
) {
}

