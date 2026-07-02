package bg.nbu.legalcaseflow.web.controller;

import bg.nbu.legalcaseflow.service.LegalServiceService;
import bg.nbu.legalcaseflow.web.dto.request.LegalServiceRequest;
import bg.nbu.legalcaseflow.web.dto.response.LegalServiceResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/legal-services")
@Tag(name = "Legal Services", description = "CRUD for rendered legal services (правни услуги)")
public class LegalServiceController {

    private final LegalServiceService legalServiceService;

    public LegalServiceController(LegalServiceService legalServiceService) {
        this.legalServiceService = legalServiceService;
    }

    @GetMapping
    public List<LegalServiceResponse> findAll() {
        return legalServiceService.findAll();
    }

    @GetMapping("/{id}")
    public LegalServiceResponse findById(@PathVariable Long id) {
        return legalServiceService.findById(id);
    }

    @GetMapping("/client/{clientId}")
    public List<LegalServiceResponse> findByClientId(@PathVariable Long clientId) {
        return legalServiceService.findByClientId(clientId);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public ResponseEntity<LegalServiceResponse> create(@Valid @RequestBody LegalServiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(legalServiceService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public LegalServiceResponse update(@PathVariable Long id, @Valid @RequestBody LegalServiceRequest request) {
        return legalServiceService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        legalServiceService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

