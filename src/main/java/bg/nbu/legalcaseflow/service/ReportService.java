package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.Payer;
import bg.nbu.legalcaseflow.domain.User;
import bg.nbu.legalcaseflow.repository.ClientRepository;
import bg.nbu.legalcaseflow.repository.DocumentRepository;
import bg.nbu.legalcaseflow.repository.LegalServiceRepository;
import bg.nbu.legalcaseflow.report.dto.LabeledAmount;
import bg.nbu.legalcaseflow.report.dto.LabeledCount;
import bg.nbu.legalcaseflow.web.mapper.ClientMapper;
import bg.nbu.legalcaseflow.web.mapper.LegalServiceMapper;
import bg.nbu.legalcaseflow.web.dto.response.ClientResponse;
import bg.nbu.legalcaseflow.web.dto.response.LegalServiceResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Statistics / справки — the analytics required by the assignment. */
@Service
@Transactional(readOnly = true)
public class ReportService {

    private final ClientRepository clientRepository;
    private final LegalServiceRepository legalServiceRepository;
    private final DocumentRepository documentRepository;
    private final CurrentUserService currentUserService;

    public ReportService(ClientRepository clientRepository,
                         LegalServiceRepository legalServiceRepository,
                         DocumentRepository documentRepository,
                         CurrentUserService currentUserService) {
        this.clientRepository = clientRepository;
        this.legalServiceRepository = legalServiceRepository;
        this.documentRepository = documentRepository;
        this.currentUserService = currentUserService;
    }

    /** Clients that have a service of a given case type. */
    public List<ClientResponse> clientsByCaseType(Long caseTypeId) {
        return legalServiceRepository.findClientsByCaseType(caseTypeId).stream()
                .map(ClientMapper::toResponse)
                .toList();
    }

    /** Distribution of services by case type (descending). */
    public List<LabeledCount> caseTypeDistribution() {
        return legalServiceRepository.countByCaseType();
    }

    /** Most common case type (first of the distribution). */
    public LabeledCount mostCommonCaseType() {
        return legalServiceRepository.countByCaseType().stream().findFirst().orElse(null);
    }

    /** Clients assigned to a given lead lawyer (= пациенти към даден личен лекар). */
    public List<ClientResponse> clientsByLeadLawyer(Long lawyerId) {
        return clientRepository.findByLeadLawyerIdOrderByFullNameAsc(lawyerId).stream()
                .map(ClientMapper::toResponse)
                .toList();
    }

    /** Number of clients per lead lawyer. */
    public List<LabeledCount> clientsPerLawyer() {
        return clientRepository.countByLeadLawyer();
    }

    /** Total amount actually paid by clients (excludes state-funded NBPP). */
    public BigDecimal totalPaidByClients() {
        BigDecimal total = legalServiceRepository.totalByPayerPaid(Payer.CLIENT);
        return total == null ? BigDecimal.ZERO : total;
    }

    /** Paid client revenue per lawyer. */
    public List<LabeledAmount> revenuePerLawyer() {
        return legalServiceRepository.revenuePerLawyer(Payer.CLIENT);
    }

    /** Number of services per lawyer. */
    public List<LabeledCount> serviceCountPerLawyer() {
        return legalServiceRepository.serviceCountPerLawyer();
    }

    /** Number of issued documents per lawyer (first = the lawyer with the most). */
    public List<LabeledCount> documentsPerLawyer() {
        return documentRepository.countByLawyer();
    }

    /** Client service history; clients can request only their own history. */
    public List<LegalServiceResponse> clientHistory(Long clientId) {
        User user = currentUserService.currentUser();
        if (currentUserService.isClient(user) && !currentUserService.clientId(user).equals(clientId)) {
            throw new AccessDeniedException("Clients can view only their own history");
        }
        return legalServiceRepository.findByClientIdOrderByDateDesc(clientId).stream()
                .map(LegalServiceMapper::toResponse)
                .toList();
    }

    /** Services filtered by optional lawyer and required period. */
    public List<LegalServiceResponse> servicesByPeriod(Long lawyerId, LocalDate from, LocalDate to) {
        currentUserService.requireAdminOrLawyer(currentUserService.currentUser());
        validatePeriod(from, to);
        if (lawyerId != null) {
            return legalServiceRepository.findByLawyerIdAndDateBetweenOrderByDateDesc(lawyerId, from, to).stream()
                    .map(LegalServiceMapper::toResponse)
                    .toList();
        }
        return legalServiceRepository.findByDateBetweenOrderByDateDesc(from, to).stream()
                .map(LegalServiceMapper::toResponse)
                .toList();
    }

    /** Month with the highest number of issued documents. */
    public LabeledCount monthWithMostDocuments() {
        currentUserService.requireAdminOrLawyer(currentUserService.currentUser());
        Map<YearMonth, Long> counts = documentRepository.findAll().stream()
                .collect(Collectors.groupingBy(d -> YearMonth.from(d.getIssueDate()), Collectors.counting()));

        return counts.entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry::getValue))
                .map(entry -> new LabeledCount(entry.getKey().toString(), entry.getValue()))
                .orElse(null);
    }

    private void validatePeriod(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Both from and to dates are required");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from date must be before or equal to to date");
        }
    }
}
