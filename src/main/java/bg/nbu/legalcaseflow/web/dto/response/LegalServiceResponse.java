package bg.nbu.legalcaseflow.web.dto.response;

import bg.nbu.legalcaseflow.domain.Payer;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LegalServiceResponse(
        Long id,
        LocalDate date,
        Long lawyerId,
        String lawyerName,
        Long clientId,
        String clientName,
        Long caseTypeId,
        String caseTypeName,
        String description,
        BigDecimal fee,
        Payer payer,
        boolean paid
) {
}

