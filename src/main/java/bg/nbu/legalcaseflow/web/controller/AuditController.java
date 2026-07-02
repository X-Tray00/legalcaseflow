package bg.nbu.legalcaseflow.web.controller;

import bg.nbu.legalcaseflow.domain.AuditAction;
import bg.nbu.legalcaseflow.domain.AuditOutcome;
import bg.nbu.legalcaseflow.domain.Role;
import bg.nbu.legalcaseflow.service.AuditQueryService;
import bg.nbu.legalcaseflow.service.AuditRestoreService;
import bg.nbu.legalcaseflow.web.dto.response.AuditEventResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/audit-events")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Audit", description = "Immutable administrator audit log")
public class AuditController {

    private final AuditQueryService queryService;
    private final AuditRestoreService restoreService;

    public AuditController(AuditQueryService queryService, AuditRestoreService restoreService) {
        this.queryService = queryService;
        this.restoreService = restoreService;
    }

    @GetMapping
    public Page<AuditEventResponse> search(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return queryService.search(actor, role, action, resource, outcome, from, to, page, size);
    }

    @GetMapping("/{id}")
    public AuditEventResponse findById(@PathVariable Long id) {
        return queryService.findById(id);
    }

    @PostMapping("/{id}/restore")
    public AuditEventResponse restore(@PathVariable Long id) {
        return restoreService.restore(id);
    }
}
