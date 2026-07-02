package bg.nbu.legalcaseflow.web.controller;

import bg.nbu.legalcaseflow.service.DocumentService;
import bg.nbu.legalcaseflow.web.dto.request.DocumentRequest;
import bg.nbu.legalcaseflow.web.dto.response.DocumentResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
@Tag(name = "Documents", description = "CRUD for issued legal documents (документи)")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping
    public List<DocumentResponse> findAll() {
        return documentService.findAll();
    }

    @GetMapping("/{id}")
    public DocumentResponse findById(@PathVariable Long id) {
        return documentService.findById(id);
    }

    @GetMapping("/client/{clientId}")
    public List<DocumentResponse> findByClientId(@PathVariable Long clientId) {
        return documentService.findByClientId(clientId);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public ResponseEntity<DocumentResponse> create(@Valid @RequestBody DocumentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(documentService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public DocumentResponse update(@PathVariable Long id, @Valid @RequestBody DocumentRequest request) {
        return documentService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

