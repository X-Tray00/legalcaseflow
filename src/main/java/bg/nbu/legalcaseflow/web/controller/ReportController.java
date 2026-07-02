package bg.nbu.legalcaseflow.web.controller;

import bg.nbu.legalcaseflow.report.dto.LabeledAmount;
import bg.nbu.legalcaseflow.report.dto.LabeledCount;
import bg.nbu.legalcaseflow.service.ReportService;
import bg.nbu.legalcaseflow.web.dto.response.ClientResponse;
import bg.nbu.legalcaseflow.web.dto.response.LegalServiceResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/reports")
@Tag(name = "Reports", description = "Statistics / справки required by the assignment")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/clients-by-case-type/{caseTypeId}")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public List<ClientResponse> clientsByCaseType(@PathVariable Long caseTypeId) {
        return reportService.clientsByCaseType(caseTypeId);
    }

    @GetMapping("/case-type-distribution")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public List<LabeledCount> caseTypeDistribution() {
        return reportService.caseTypeDistribution();
    }

    @GetMapping("/most-common-case-type")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public LabeledCount mostCommonCaseType() {
        return reportService.mostCommonCaseType();
    }

    @GetMapping("/clients-by-lead-lawyer/{lawyerId}")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public List<ClientResponse> clientsByLeadLawyer(@PathVariable Long lawyerId) {
        return reportService.clientsByLeadLawyer(lawyerId);
    }

    @GetMapping("/clients-per-lawyer")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public List<LabeledCount> clientsPerLawyer() {
        return reportService.clientsPerLawyer();
    }

    @GetMapping("/total-paid-by-clients")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public BigDecimal totalPaidByClients() {
        return reportService.totalPaidByClients();
    }

    @GetMapping("/revenue-per-lawyer")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public List<LabeledAmount> revenuePerLawyer() {
        return reportService.revenuePerLawyer();
    }

    @GetMapping("/service-count-per-lawyer")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public List<LabeledCount> serviceCountPerLawyer() {
        return reportService.serviceCountPerLawyer();
    }

    @GetMapping("/documents-per-lawyer")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public List<LabeledCount> documentsPerLawyer() {
        return reportService.documentsPerLawyer();
    }

    @GetMapping("/client-history/{clientId}")
    public List<LegalServiceResponse> clientHistory(@PathVariable Long clientId) {
        return reportService.clientHistory(clientId);
    }

    @GetMapping("/services-by-period")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public List<LegalServiceResponse> servicesByPeriod(
            @RequestParam(required = false) Long lawyerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.servicesByPeriod(lawyerId, from, to);
    }

    @GetMapping("/month-with-most-documents")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public LabeledCount monthWithMostDocuments() {
        return reportService.monthWithMostDocuments();
    }
}
