package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.*;
import bg.nbu.legalcaseflow.exception.ConflictException;
import bg.nbu.legalcaseflow.exception.NotFoundException;
import bg.nbu.legalcaseflow.repository.InvoiceRepository;
import bg.nbu.legalcaseflow.repository.LegalServiceRepository;
import bg.nbu.legalcaseflow.web.mapper.InvoiceMapper;
import bg.nbu.legalcaseflow.web.dto.request.InvoiceRequest;
import bg.nbu.legalcaseflow.web.dto.response.InvoiceResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@Transactional
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final LegalServiceRepository legalServiceRepository;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final SoftDeleteService softDeleteService;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          LegalServiceRepository legalServiceRepository,
                          CurrentUserService currentUserService,
                          AuditService auditService,
                          SoftDeleteService softDeleteService) {
        this.invoiceRepository = invoiceRepository;
        this.legalServiceRepository = legalServiceRepository;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.softDeleteService = softDeleteService;
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> findAll() {
        return visibleInvoices(currentUserService.currentUser()).stream().map(InvoiceMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public InvoiceResponse findById(Long id) {
        User user = currentUserService.currentUser();
        Invoice invoice = get(id);
        requireCanView(user, invoice);
        return InvoiceMapper.toResponse(invoice);
    }

    public InvoiceResponse create(InvoiceRequest request) {
        User user = currentUserService.currentUser();
        currentUserService.requireAdminOrLawyer(user);
        Invoice invoice = new Invoice();
        apply(invoice, request, user);
        Invoice saved = invoiceRepository.save(invoice);
        syncServicePaidFromInvoice(saved);
        InvoiceResponse response = InvoiceMapper.toResponse(saved);
        auditService.record(AuditAction.CREATE, "invoices", response.id(), null, response, null);
        return response;
    }

    public InvoiceResponse update(Long id, InvoiceRequest request) {
        User user = currentUserService.currentUser();
        Invoice invoice = get(id);
        requireCanEdit(user, invoice);
        Long previousServiceId = invoice.getLegalService().getId();
        InvoiceResponse before = InvoiceMapper.toResponse(invoice);
        apply(invoice, request, user);
        Invoice saved = invoiceRepository.save(invoice);
        syncServicePaidFromInvoice(saved);
        if (!previousServiceId.equals(saved.getLegalService().getId())) {
            syncServicePaid(previousServiceId);
        }
        InvoiceResponse response = InvoiceMapper.toResponse(saved);
        auditService.record(AuditAction.UPDATE, "invoices", id, before, response, null);
        return response;
    }

    public InvoiceResponse updateStatus(Long id, InvoiceStatus status) {
        User user = currentUserService.currentUser();
        Invoice invoice = get(id);
        requireCanEdit(user, invoice);
        InvoiceResponse before = InvoiceMapper.toResponse(invoice);
        invoice.setStatus(status);
        Invoice saved = invoiceRepository.save(invoice);
        syncServicePaidFromInvoice(saved);
        InvoiceResponse response = InvoiceMapper.toResponse(saved);
        auditService.record(AuditAction.UPDATE, "invoices", id, before, response,
                java.util.Map.of("operation", "status-change"));
        return response;
    }

    private void syncServicePaidFromInvoice(Invoice invoice) {
        syncServicePaid(invoice.getLegalService().getId());
    }

    private void syncServicePaid(Long serviceId) {
        LegalService service = legalServiceRepository.findById(serviceId)
                .orElseThrow(() -> NotFoundException.of("LegalService", serviceId));
        boolean paid = invoiceRepository.existsByLegalService_IdAndStatus(serviceId, InvoiceStatus.PAID);
        if (service.isPaid() != paid) {
            service.setPaid(paid);
            legalServiceRepository.save(service);
        }
    }

    public void delete(Long id) {
        User user = currentUserService.currentUser();
        Invoice invoice = get(id);
        requireCanEdit(user, invoice);
        Long serviceId = invoice.getLegalService().getId();
        InvoiceResponse before = InvoiceMapper.toResponse(invoice);
        softDeleteService.delete("invoices", id);
        syncServicePaid(serviceId);
        auditService.record(AuditAction.DELETE, "invoices", id, before, null, null);
    }

    @Transactional(readOnly = true)
    public String exportCsv(boolean safTLite) {
        User user = currentUserService.currentUser();
        List<Invoice> invoices = visibleInvoices(user);
        StringBuilder out = new StringBuilder();
        if (safTLite) {
            out.append("InvoiceNo,IssueDate,DueDate,CustomerName,LawyerName,Payer,Amount,Status,SourceLegalServiceId\n");
        } else {
            out.append("invoiceNumber,issueDate,dueDate,client,lawyer,payer,amount,status,legalServiceId\n");
        }
        for (Invoice invoice : invoices) {
            LegalService service = invoice.getLegalService();
            appendCsv(out,
                    invoice.getInvoiceNumber(),
                    invoice.getIssueDate(),
                    invoice.getDueDate(),
                    service.getClient().getFullName(),
                    service.getLawyer().getFullName(),
                    invoice.getPayer(),
                    invoice.getAmount(),
                    invoice.getStatus(),
                    service.getId()
            );
        }
        return out.toString();
    }

    private List<Invoice> visibleInvoices(User user) {
        if (currentUserService.isClient(user)) {
            return invoiceRepository.findByLegalServiceClientIdOrderByIssueDateDesc(currentUserService.clientId(user));
        }
        if (currentUserService.isLawyer(user)) {
            return invoiceRepository.findByLegalServiceLawyerIdOrderByIssueDateDesc(currentUserService.lawyerId(user));
        }
        return invoiceRepository.findAllByOrderByIssueDateDesc();
    }

    private Invoice get(Long id) {
        return invoiceRepository.findById(id).orElseThrow(() -> NotFoundException.of("Invoice", id));
    }

    private void apply(Invoice invoice, InvoiceRequest request, User user) {
        LegalService service = legalServiceRepository.findById(request.legalServiceId())
                .orElseThrow(() -> NotFoundException.of("LegalService", request.legalServiceId()));
        if (currentUserService.isLawyer(user)
                && !currentUserService.lawyerId(user).equals(service.getLawyer().getId())) {
            throw new AccessDeniedException("Lawyers can invoice only their own services");
        }

        String invoiceNumber = normalizeInvoiceNumber(request.invoiceNumber());
        if (invoiceNumber == null) {
            invoiceNumber = nextInvoiceNumber(request.issueDate());
        }
        if (!invoiceNumber.equals(invoice.getInvoiceNumber()) && invoiceRepository.existsByInvoiceNumber(invoiceNumber)) {
            throw new ConflictException("Invoice number already exists: " + invoiceNumber);
        }

        validateDates(request.issueDate(), request.dueDate());
        BigDecimal amount = service.getFee();

        invoice.setInvoiceNumber(invoiceNumber);
        invoice.setLegalService(service);
        invoice.setIssueDate(request.issueDate());
        invoice.setDueDate(request.dueDate());
        invoice.setAmount(amount);
        invoice.setPayer(service.getPayer());
        invoice.setStatus(request.status() == null ? InvoiceStatus.DRAFT : request.status());
    }

    private void requireCanView(User user, Invoice invoice) {
        if (currentUserService.isAdmin(user)) {
            return;
        }
        LegalService service = invoice.getLegalService();
        if (currentUserService.isLawyer(user)
                && currentUserService.lawyerId(user).equals(service.getLawyer().getId())) {
            return;
        }
        if (currentUserService.isClient(user)
                && currentUserService.clientId(user).equals(service.getClient().getId())) {
            return;
        }
        throw new AccessDeniedException("Cannot view this invoice");
    }

    private void requireCanEdit(User user, Invoice invoice) {
        if (currentUserService.isAdmin(user)) {
            return;
        }
        if (currentUserService.isLawyer(user)
                && currentUserService.lawyerId(user).equals(invoice.getLegalService().getLawyer().getId())) {
            return;
        }
        throw new AccessDeniedException("Cannot edit this invoice");
    }

    private String nextInvoiceNumber(LocalDate issueDate) {
        int year = issueDate.getYear();
        long next = invoiceRepository.count() + 1;
        String candidate;
        do {
            candidate = "LCF-" + year + "-" + String.format("%04d", next++);
        } while (invoiceRepository.existsByInvoiceNumber(candidate));
        return candidate;
    }

    private String normalizeInvoiceNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void validateDates(LocalDate issueDate, LocalDate dueDate) {
        if (dueDate.isBefore(issueDate)) {
            throw new IllegalArgumentException("Due date cannot be before issue date");
        }
    }

    private void appendCsv(StringBuilder out, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(csv(values[i]));
        }
        out.append('\n');
    }

    private String csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        // Neutralize CSV/formula injection: a leading =, +, -, @, tab or CR makes
        // spreadsheet apps treat the cell as a formula. Prefix it with an apostrophe.
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
