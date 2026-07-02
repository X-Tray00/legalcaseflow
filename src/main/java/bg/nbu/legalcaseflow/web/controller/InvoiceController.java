package bg.nbu.legalcaseflow.web.controller;

import bg.nbu.legalcaseflow.domain.InvoiceStatus;
import bg.nbu.legalcaseflow.service.InvoiceService;
import bg.nbu.legalcaseflow.web.dto.request.InvoiceRequest;
import bg.nbu.legalcaseflow.web.dto.response.InvoiceResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/invoices")
@Tag(name = "Invoices", description = "Invoices and accounting exports")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping
    public List<InvoiceResponse> findAll() {
        return invoiceService.findAll();
    }

    @GetMapping("/{id}")
    public InvoiceResponse findById(@PathVariable Long id) {
        return invoiceService.findById(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public ResponseEntity<InvoiceResponse> create(@Valid @RequestBody InvoiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invoiceService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public InvoiceResponse update(@PathVariable Long id, @Valid @RequestBody InvoiceRequest request) {
        return invoiceService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public InvoiceResponse updateStatus(@PathVariable Long id, @RequestParam InvoiceStatus status) {
        return invoiceService.updateStatus(id, status);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        invoiceService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public ResponseEntity<String> exportCsv() {
        return csv("legalcaseflow-invoices.csv", invoiceService.exportCsv(false));
    }

    @GetMapping(value = "/saf-t-lite.csv", produces = "text/csv")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public ResponseEntity<String> exportSafTLiteCsv() {
        return csv("legalcaseflow-saf-t-lite.csv", invoiceService.exportCsv(true));
    }

    private ResponseEntity<String> csv(String fileName, String body) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(body);
    }
}
