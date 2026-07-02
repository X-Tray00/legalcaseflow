package bg.nbu.legalcaseflow.web.dto.response;

import bg.nbu.legalcaseflow.domain.DraftTemplateType;

import java.time.Instant;

public record DocumentDraftResponse(
        DraftTemplateType templateType,
        String title,
        String content,
        Instant generatedAt
) {
}

