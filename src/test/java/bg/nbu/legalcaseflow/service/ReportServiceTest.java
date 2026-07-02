package bg.nbu.legalcaseflow.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ReportServiceTest {

    @Autowired
    private ReportService reportService;

    @Test
    void caseTypeDistributionIsNotEmpty() {
        assertThat(reportService.caseTypeDistribution()).isNotEmpty();
    }

    @Test
    void totalPaidByClientsIsNonNegative() {
        assertThat(reportService.totalPaidByClients()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void mostCommonCaseTypeIsResolved() {
        assertThat(reportService.mostCommonCaseType()).isNotNull();
        assertThat(reportService.mostCommonCaseType().count()).isPositive();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void servicesByPeriodReturnsSeededServices() {
        assertThat(reportService.servicesByPeriod(null, LocalDate.now().minusDays(60), LocalDate.now()))
                .isNotEmpty();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void monthWithMostDocumentsIsResolved() {
        assertThat(reportService.monthWithMostDocuments()).isNotNull();
        assertThat(reportService.monthWithMostDocuments().count()).isPositive();
    }

    @Test
    void clientsByLeadLawyerReturnsOnlyThatLawyersClients() {
        var clients = reportService.clientsByLeadLawyer(1L);
        assertThat(clients).isNotEmpty();
        assertThat(clients).allMatch(c -> c.leadLawyerId() != null && c.leadLawyerId().equals(1L));
    }
}
