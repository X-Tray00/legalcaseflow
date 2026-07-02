package bg.nbu.legalcaseflow.web.dto.request;

import bg.nbu.legalcaseflow.domain.DraftTemplateType;
import jakarta.validation.constraints.NotNull;

public record DocumentDraftRequest(
        @NotNull Long clientId,
        @NotNull Long lawyerId,
        @NotNull Long caseTypeId,
        @NotNull DraftTemplateType templateType,
        String facts
) {
}

