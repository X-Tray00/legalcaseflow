package bg.nbu.legalcaseflow.web.mapper;

import bg.nbu.legalcaseflow.domain.Client;
import bg.nbu.legalcaseflow.domain.Lawyer;
import bg.nbu.legalcaseflow.web.dto.response.ClientResponse;

public final class ClientMapper {

    private ClientMapper() {
    }

    public static ClientResponse toResponse(Client client) {
        return toResponse(client, null);
    }

    public static ClientResponse toResponse(Client client, String username) {
        Lawyer lead = client.getLeadLawyer();
        return new ClientResponse(
                client.getId(),
                client.getFullName(),
                client.getIdentifier(),
                client.getContact(),
                client.isLegalAidEligible(),
                lead == null ? null : lead.getId(),
                lead == null ? null : lead.getFullName(),
                username
        );
    }
}
