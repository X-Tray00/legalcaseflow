package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.LegalService;
import bg.nbu.legalcaseflow.domain.Payer;
import bg.nbu.legalcaseflow.repository.CaseTypeRepository;
import bg.nbu.legalcaseflow.repository.ClientRepository;
import bg.nbu.legalcaseflow.repository.LawyerRepository;
import bg.nbu.legalcaseflow.repository.LegalServiceRepository;
import bg.nbu.legalcaseflow.web.dto.request.LegalServiceRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class LegalServiceSecurityTest {

    @Autowired
    private LegalServiceService legalServiceService;

    @Autowired
    private LegalServiceRepository legalServiceRepository;

    @Autowired
    private LawyerRepository lawyerRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private CaseTypeRepository caseTypeRepository;

    @Test
    @WithMockUser(username = "maria", roles = "CLIENT")
    void clientSeesOnlyOwnServices() {
        var services = legalServiceService.findAll();

        assertThat(services).isNotEmpty();
        assertThat(services).allMatch(service -> "Мария Стоянова".equals(service.clientName()));
    }

    @Test
    @WithMockUser(username = "maria", roles = "CLIENT")
    void clientCannotViewAnotherClientsService() {
        LegalService otherClientService = legalServiceRepository.findAll().stream()
                .filter(service -> !"Мария Стоянова".equals(service.getClient().getFullName()))
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> legalServiceService.findById(otherClientService.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(username = "ivanov", roles = "LAWYER")
    void lawyerCannotUpdateServicePerformedByAnotherLawyer() {
        LegalService otherLawyersService = legalServiceRepository.findAll().stream()
                .filter(service -> !"BAR-1001".equals(service.getLawyer().getRegistrationNumber()))
                .findFirst()
                .orElseThrow();

        LegalServiceRequest request = requestFrom(otherLawyersService);

        assertThatThrownBy(() -> legalServiceService.update(otherLawyersService.getId(), request))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(username = "ivanov", roles = "LAWYER")
    void lawyerCanUpdateOwnService() {
        LegalService ownService = legalServiceRepository.findAll().stream()
                .filter(service -> "BAR-1001".equals(service.getLawyer().getRegistrationNumber()))
                .findFirst()
                .orElseThrow();
        LegalServiceRequest request = new LegalServiceRequest(
                ownService.getDate(),
                ownService.getLawyer().getId(),
                ownService.getClient().getId(),
                ownService.getCaseType().getId(),
                "Обновено описание",
                ownService.getFee(),
                ownService.isPaid()
        );

        var updated = legalServiceService.update(ownService.getId(), request);

        assertThat(updated.description()).isEqualTo("Обновено описание");
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void payerIsDerivedFromClientLegalAidStatus() {
        var legalAidClient = clientRepository.findAll().stream()
                .filter(client -> client.isLegalAidEligible())
                .findFirst()
                .orElseThrow();
        var lawyer = lawyerRepository.findAll().getFirst();
        var caseType = caseTypeRepository.findAll().getFirst();
        LegalServiceRequest request = new LegalServiceRequest(
                LocalDate.now(),
                lawyer.getId(),
                legalAidClient.getId(),
                caseType.getId(),
                "Тестова услуга с правна помощ",
                new BigDecimal("99.00"),
                true
        );

        var created = legalServiceService.create(request);

        assertThat(created.payer()).isEqualTo(Payer.NBPP);
    }

    private LegalServiceRequest requestFrom(LegalService service) {
        return new LegalServiceRequest(
                service.getDate(),
                service.getLawyer().getId(),
                service.getClient().getId(),
                service.getCaseType().getId(),
                service.getDescription(),
                service.getFee(),
                service.isPaid()
        );
    }
}

