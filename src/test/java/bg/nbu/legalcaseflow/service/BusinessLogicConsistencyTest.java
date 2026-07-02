package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.Client;
import bg.nbu.legalcaseflow.domain.InvoiceStatus;
import bg.nbu.legalcaseflow.domain.LegalService;
import bg.nbu.legalcaseflow.domain.Payer;
import bg.nbu.legalcaseflow.repository.CaseTypeRepository;
import bg.nbu.legalcaseflow.repository.ClientRepository;
import bg.nbu.legalcaseflow.repository.LawyerRepository;
import bg.nbu.legalcaseflow.repository.LegalServiceRepository;
import bg.nbu.legalcaseflow.web.dto.request.InvoiceRequest;
import bg.nbu.legalcaseflow.web.dto.response.InvoiceResponse;
import bg.nbu.legalcaseflow.web.dto.request.LegalServiceRequest;
import bg.nbu.legalcaseflow.web.dto.response.LegalServiceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the business-logic decisions:
 * BL-1 — the invoice DRAFT/ISSUED/PAID lifecycle drives the {@code LegalService.paid}
 *        flag that every revenue report reads.
 * BL-2 — the payer is a per-case snapshot frozen at creation, not silently re-flipped
 *        when the client's legal-aid eligibility changes later.
 */
@SpringBootTest
@Transactional
class BusinessLogicConsistencyTest {

    @Autowired
    private LegalServiceService legalServiceService;
    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private ReportService reportService;
    @Autowired
    private LegalServiceRepository legalServiceRepository;
    @Autowired
    private ClientRepository clientRepository;
    @Autowired
    private LawyerRepository lawyerRepository;
    @Autowired
    private CaseTypeRepository caseTypeRepository;

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void markingInvoicePaidFlipsServicePaidAndFeedsRevenueReport() {
        Client payingClient = clientRepository.findAll().stream()
                .filter(c -> !c.isLegalAidEligible())
                .findFirst().orElseThrow();

        BigDecimal before = reportService.totalPaidByClients();
        LegalServiceResponse service = createService(payingClient, new BigDecimal("123.00"), true);
        // The request cannot mark a service paid without a paid invoice.
        assertThat(legalServiceRepository.findById(service.id()).orElseThrow().isPaid()).isFalse();

        InvoiceResponse invoice = invoiceService.create(
                new InvoiceRequest(null, service.id(), LocalDate.now(), LocalDate.now().plusDays(30), null));
        // A DRAFT invoice does not mark the service paid.
        assertThat(legalServiceRepository.findById(service.id()).orElseThrow().isPaid()).isFalse();

        invoiceService.updateStatus(invoice.id(), InvoiceStatus.PAID);
        assertThat(legalServiceRepository.findById(service.id()).orElseThrow().isPaid()).isTrue();
        assertThat(reportService.totalPaidByClients()).isEqualByComparingTo(before.add(new BigDecimal("123.00")));

        InvoiceResponse secondInvoice = invoiceService.create(
                new InvoiceRequest(null, service.id(), LocalDate.now(), LocalDate.now().plusDays(30), null));
        assertThat(legalServiceRepository.findById(service.id()).orElseThrow().isPaid()).isTrue();

        // Payment remains true only while at least one active invoice is paid.
        invoiceService.updateStatus(invoice.id(), InvoiceStatus.ISSUED);
        assertThat(legalServiceRepository.findById(service.id()).orElseThrow().isPaid()).isFalse();
        assertThat(reportService.totalPaidByClients()).isEqualByComparingTo(before);

        invoiceService.updateStatus(secondInvoice.id(), InvoiceStatus.PAID);
        assertThat(legalServiceRepository.findById(service.id()).orElseThrow().isPaid()).isTrue();

        invoiceService.delete(secondInvoice.id());
        assertThat(legalServiceRepository.findById(service.id()).orElseThrow().isPaid()).isFalse();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void payerIsFrozenAtCreationAndNotFlippedByLaterEligibilityChange() {
        Client client = clientRepository.findAll().stream()
                .filter(c -> !c.isLegalAidEligible())
                .findFirst().orElseThrow();

        LegalServiceResponse service = createService(client, new BigDecimal("50.00"), false);
        assertThat(service.payer()).isEqualTo(Payer.CLIENT);

        // Client later becomes legal-aid eligible.
        client.setLegalAidEligible(true);
        clientRepository.save(client);

        // Editing an unrelated field must NOT silently move the payer to NBPP.
        LegalService entity = legalServiceRepository.findById(service.id()).orElseThrow();
        LegalServiceResponse updated = legalServiceService.update(service.id(), new LegalServiceRequest(
                entity.getDate(), entity.getLawyer().getId(), client.getId(), entity.getCaseType().getId(),
                "Променено описание", entity.getFee(), entity.isPaid()));
        assertThat(updated.payer()).isEqualTo(Payer.CLIENT);

        // A brand new service for the now-eligible client picks up NBPP.
        assertThat(createService(client, new BigDecimal("70.00"), false).payer()).isEqualTo(Payer.NBPP);
    }

    private LegalServiceResponse createService(Client client, BigDecimal fee, boolean paid) {
        var lawyer = lawyerRepository.findAll().getFirst();
        var caseType = caseTypeRepository.findAll().getFirst();
        return legalServiceService.create(new LegalServiceRequest(
                LocalDate.now(), lawyer.getId(), client.getId(), caseType.getId(),
                "Тестова услуга", fee, paid));
    }
}
