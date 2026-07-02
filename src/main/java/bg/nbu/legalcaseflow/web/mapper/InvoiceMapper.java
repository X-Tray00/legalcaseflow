package bg.nbu.legalcaseflow.web.mapper;

import bg.nbu.legalcaseflow.domain.Client;
import bg.nbu.legalcaseflow.domain.Invoice;
import bg.nbu.legalcaseflow.domain.Lawyer;
import bg.nbu.legalcaseflow.domain.LegalService;
import bg.nbu.legalcaseflow.web.dto.response.InvoiceResponse;

public final class InvoiceMapper {

    private InvoiceMapper() {
    }

    public static InvoiceResponse toResponse(Invoice invoice) {
        LegalService service = invoice.getLegalService();
        Client client = service.getClient();
        Lawyer lawyer = service.getLawyer();
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                service.getId(),
                client.getId(),
                client.getFullName(),
                lawyer.getId(),
                lawyer.getFullName(),
                invoice.getIssueDate(),
                invoice.getDueDate(),
                invoice.getAmount(),
                invoice.getPayer(),
                invoice.getStatus()
        );
    }
}

