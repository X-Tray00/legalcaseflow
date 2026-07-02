package bg.nbu.legalcaseflow.web.controller;

import bg.nbu.legalcaseflow.service.DocumentDraftService;
import bg.nbu.legalcaseflow.web.dto.request.DocumentDraftRequest;
import bg.nbu.legalcaseflow.web.dto.response.DocumentDraftResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/document-drafts")
@Tag(name = "Document Drafts", description = "Template-based AI-style legal document drafts")
public class DocumentDraftController {

    private final DocumentDraftService documentDraftService;

    public DocumentDraftController(DocumentDraftService documentDraftService) {
        this.documentDraftService = documentDraftService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','LAWYER')")
    public DocumentDraftResponse generate(@Valid @RequestBody DocumentDraftRequest request) {
        return documentDraftService.generate(request);
    }
}

