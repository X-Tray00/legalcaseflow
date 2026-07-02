package bg.nbu.legalcaseflow.web.dto.request;

import bg.nbu.legalcaseflow.domain.InvoiceStatus;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record InvoiceRequest(
        String invoiceNumber,
        @NotNull Long legalServiceId,
        @NotNull LocalDate issueDate,
        @NotNull LocalDate dueDate,
        InvoiceStatus status
) {
}

