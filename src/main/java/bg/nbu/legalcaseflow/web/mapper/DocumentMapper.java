package bg.nbu.legalcaseflow.web.mapper;

import bg.nbu.legalcaseflow.domain.Client;
import bg.nbu.legalcaseflow.domain.Document;
import bg.nbu.legalcaseflow.domain.Lawyer;
import bg.nbu.legalcaseflow.web.dto.response.DocumentResponse;

public final class DocumentMapper {

    private DocumentMapper() {
    }

    public static DocumentResponse toResponse(Document document) {
        Client client = document.getClient();
        Lawyer lawyer = document.getLawyer();
        return new DocumentResponse(
                document.getId(),
                document.getTitle(),
                document.getContent(),
                client.getId(),
                client.getFullName(),
                lawyer.getId(),
                lawyer.getFullName(),
                document.getIssueDate(),
                document.getValidityDays()
        );
    }
}
