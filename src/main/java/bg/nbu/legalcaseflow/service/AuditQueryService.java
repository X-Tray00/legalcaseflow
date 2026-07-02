package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.*;
import bg.nbu.legalcaseflow.exception.NotFoundException;
import bg.nbu.legalcaseflow.repository.AuditEventRepository;
import bg.nbu.legalcaseflow.web.dto.response.AuditEventResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Transactional(readOnly = true)
public class AuditQueryService {

    private final AuditEventRepository repository;

    public AuditQueryService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public Page<AuditEventResponse> search(String actor, Role role, AuditAction action, String resource,
                                           AuditOutcome outcome, Instant from, Instant to, int page, int size) {
        Specification<AuditEvent> spec = Specification.where(null);
        if (actor != null && !actor.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("actorUsername")),
                    "%" + actor.trim().toLowerCase() + "%"));
        }
        if (role != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("actorRole"), role));
        if (action != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("action"), action));
        if (resource != null && !resource.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("resourceType"), resource));
        }
        if (outcome != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("outcome"), outcome));
        if (from != null) spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
        if (to != null) spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("occurredAt"), to));

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        return repository.findAll(spec, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id")))
                .map(this::toResponse);
    }

    public AuditEventResponse findById(Long id) {
        return toResponse(get(id));
    }

    public AuditEvent get(Long id) {
        return repository.findById(id).orElseThrow(() -> NotFoundException.of("AuditEvent", id));
    }

    public boolean isRestorable(AuditEvent event) {
        return event.getOutcome() == AuditOutcome.SUCCESS
                && (event.getAction() == AuditAction.CREATE
                || event.getAction() == AuditAction.UPDATE
                || event.getAction() == AuditAction.DELETE)
                && !repository.existsBySourceAuditEventId(event.getId())
                && repository.findTopByResourceTypeAndResourceIdOrderByIdDesc(event.getResourceType(), event.getResourceId())
                .map(latest -> latest.getId().equals(event.getId()))
                .orElse(false);
    }

    private AuditEventResponse toResponse(AuditEvent event) {
        return new AuditEventResponse(event.getId(), event.getOccurredAt(), event.getActorId(),
                event.getActorUsername(), event.getActorRole(), event.getAction(), event.getOutcome(),
                event.getResourceType(), event.getResourceId(), event.getBeforeState(), event.getAfterState(),
                event.getMetadata(), event.getRequestMethod(), event.getRequestPath(), event.getIpAddress(),
                event.getUserAgent(), event.getSourceAuditEventId(), isRestorable(event));
    }
}
