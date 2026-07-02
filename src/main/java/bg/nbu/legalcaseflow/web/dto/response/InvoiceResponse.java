package bg.nbu.legalcaseflow.web.dto.response;

import bg.nbu.legalcaseflow.domain.InvoiceStatus;
import bg.nbu.legalcaseflow.domain.Payer;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InvoiceResponse(
        Long id,
        String invoiceNumber,
        Long legalServiceId,
        Long clientId,
        String clientName,
        Long lawyerId,
        String lawyerName,
        LocalDate issueDate,
        LocalDate dueDate,
        BigDecimal amount,
        Payer payer,
        InvoiceStatus status
) {
}

