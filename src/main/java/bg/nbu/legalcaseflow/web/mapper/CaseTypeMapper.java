package bg.nbu.legalcaseflow.web.mapper;

import bg.nbu.legalcaseflow.domain.CaseType;
import bg.nbu.legalcaseflow.web.dto.response.CaseTypeResponse;

public final class CaseTypeMapper {

    private CaseTypeMapper() {
    }

    public static CaseTypeResponse toResponse(CaseType caseType) {
        return new CaseTypeResponse(
                caseType.getId(),
                caseType.getName(),
                caseType.getDescription()
        );
    }
}

