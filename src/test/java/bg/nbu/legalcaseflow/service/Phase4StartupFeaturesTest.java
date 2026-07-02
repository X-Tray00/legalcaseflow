package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.AppointmentStatus;
import bg.nbu.legalcaseflow.domain.DraftTemplateType;
import bg.nbu.legalcaseflow.repository.CaseTypeRepository;
import bg.nbu.legalcaseflow.repository.ClientRepository;
import bg.nbu.legalcaseflow.repository.LawyerRepository;
import bg.nbu.legalcaseflow.repository.LegalServiceRepository;
import bg.nbu.legalcaseflow.web.dto.request.AppointmentRequest;
import bg.nbu.legalcaseflow.web.dto.request.DocumentDraftRequest;
import bg.nbu.legalcaseflow.web.dto.request.DocumentRequest;
import bg.nbu.legalcaseflow.web.dto.request.InvoiceRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class Phase4StartupFeaturesTest {

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private DocumentDraftService documentDraftService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private LawyerRepository lawyerRepository;

    @Autowired
    private CaseTypeRepository caseTypeRepository;

    @Autowired
    private LegalServiceRepository legalServiceRepository;

    @Test
    @WithMockUser(username = "maria", roles = "CLIENT")
    void clientSeesOnlyOwnAppointmentsAndInvoices() {
        assertThat(appointmentService.findAll()).allMatch(a -> "Мария Стоянова".equals(a.clientName()));
        assertThat(invoiceService.findAll()).allMatch(i -> "Мария Стоянова".equals(i.clientName()));
    }

    @Test
    @WithMockUser(username = "maria", roles = "CLIENT")
    void clientCanRequestOwnAppointment() {
        var maria = clientRepository.findAll().stream()
                .filter(client -> "Мария Стоянова".equals(client.getFullName()))
                .findFirst()
                .orElseThrow();
        var lawyer = lawyerRepository.findAll().getFirst();

        var created = appointmentService.create(new AppointmentRequest(
                maria.getId(),
                lawyer.getId(),
                LocalDateTime.now().plusDays(9).withHour(9).withMinute(0).withSecond(0).withNano(0),
                AppointmentStatus.REQUESTED,
                "Онлайн заявка за консултация",
                "Клиентска заявка"
        ));

        assertThat(created.status()).isEqualTo(AppointmentStatus.REQUESTED);
        assertThat(created.clientName()).isEqualTo("Мария Стоянова");
    }

    @Test
    @WithMockUser(username = "ivanov", roles = "LAWYER")
    void lawyerCannotInvoiceAnotherLawyersService() {
        var otherLawyersService = legalServiceRepository.findAll().stream()
                .filter(service -> !"BAR-1001".equals(service.getLawyer().getRegistrationNumber()))
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> invoiceService.create(new InvoiceRequest(
                null,
                otherLawyersService.getId(),
                LocalDate.now(),
                LocalDate.now().plusDays(14),
                null
        ))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void invoiceExportContainsCsvHeaderAndSeedInvoice() {
        String csv = invoiceService.exportCsv(false);

        assertThat(csv).startsWith("invoiceNumber,issueDate,dueDate");
        assertThat(csv).contains("LCF-" + LocalDate.now().getYear() + "-0001");
    }

    @Test
    @WithMockUser(username = "ivanov", roles = "LAWYER")
    void lawyerCanGenerateDocumentDraftAsSelf() {
        var maria = clientRepository.findAll().stream()
                .filter(client -> "Мария Стоянова".equals(client.getFullName()))
                .findFirst()
                .orElseThrow();
        var ivanov = lawyerRepository.findByRegistrationNumber("BAR-1001").orElseThrow();
        var caseType = caseTypeRepository.findAll().getFirst();

        var draft = documentDraftService.generate(new DocumentDraftRequest(
                maria.getId(),
                ivanov.getId(),
                caseType.getId(),
                DraftTemplateType.CONSULTATION_SUMMARY,
                "Клиентът предостави договор и кореспонденция."
        ));

        assertThat(draft.title()).contains("консултация");
        assertThat(draft.content()).contains("Мария Стоянова");
        assertThat(draft.content()).contains("Иван Иванов");
        assertThat(draft.content()).contains("Клиентът предостави договор");
    }

    @Test
    @WithMockUser(username = "ivanov", roles = "LAWYER")
    void generatedDraftContentCanBeSavedAsDocument() {
        var maria = clientRepository.findAll().stream()
                .filter(client -> "Мария Стоянова".equals(client.getFullName()))
                .findFirst()
                .orElseThrow();
        var ivanov = lawyerRepository.findByRegistrationNumber("BAR-1001").orElseThrow();
        String content = "Редактируем текст на генерираната чернова.";

        var created = documentService.create(new DocumentRequest(
                "Резюме на консултация",
                content,
                maria.getId(),
                ivanov.getId(),
                LocalDate.now(),
                30
        ));

        assertThat(created.content()).isEqualTo(content);
        assertThat(documentService.findById(created.id()).content()).isEqualTo(content);
    }
}
