package bg.nbu.legalcaseflow.web.controller;

import bg.nbu.legalcaseflow.service.CaseTypeService;
import bg.nbu.legalcaseflow.web.dto.request.CaseTypeRequest;
import bg.nbu.legalcaseflow.web.dto.response.CaseTypeResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/case-types")
@Tag(name = "Case Types", description = "CRUD for legal matter types (видове казуси)")
public class CaseTypeController {

    private final CaseTypeService caseTypeService;

    public CaseTypeController(CaseTypeService caseTypeService) {
        this.caseTypeService = caseTypeService;
    }

    @GetMapping
    public List<CaseTypeResponse> findAll() {
        return caseTypeService.findAll();
    }

    @GetMapping("/{id}")
    public CaseTypeResponse findById(@PathVariable Long id) {
        return caseTypeService.findById(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public ResponseEntity<CaseTypeResponse> create(@Valid @RequestBody CaseTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(caseTypeService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public CaseTypeResponse update(@PathVariable Long id, @Valid @RequestBody CaseTypeRequest request) {
        return caseTypeService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        caseTypeService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

