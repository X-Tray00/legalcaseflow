package bg.nbu.legalcaseflow.web.mapper;

import bg.nbu.legalcaseflow.domain.CaseType;
import bg.nbu.legalcaseflow.domain.Client;
import bg.nbu.legalcaseflow.domain.Lawyer;
import bg.nbu.legalcaseflow.domain.LegalService;
import bg.nbu.legalcaseflow.web.dto.response.LegalServiceResponse;

public final class LegalServiceMapper {

    private LegalServiceMapper() {
    }

    public static LegalServiceResponse toResponse(LegalService service) {
        Lawyer lawyer = service.getLawyer();
        Client client = service.getClient();
        CaseType caseType = service.getCaseType();
        return new LegalServiceResponse(
                service.getId(),
                service.getDate(),
                lawyer.getId(),
                lawyer.getFullName(),
                client.getId(),
                client.getFullName(),
                caseType.getId(),
                caseType.getName(),
                service.getDescription(),
                service.getFee(),
                service.getPayer(),
                service.isPaid()
        );
    }
}

