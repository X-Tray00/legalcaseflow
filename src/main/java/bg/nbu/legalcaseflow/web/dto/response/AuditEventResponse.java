package bg.nbu.legalcaseflow.web.dto.response;

import bg.nbu.legalcaseflow.domain.AuditAction;
import bg.nbu.legalcaseflow.domain.AuditOutcome;
import bg.nbu.legalcaseflow.domain.Role;

import java.time.Instant;

public record AuditEventResponse(
        Long id,
        Instant occurredAt,
        Long actorId,
        String actorUsername,
        Role actorRole,
        AuditAction action,
        AuditOutcome outcome,
        String resourceType,
        Long resourceId,
        String beforeState,
        String afterState,
        String metadata,
        String requestMethod,
        String requestPath,
        String ipAddress,
        String userAgent,
        Long sourceAuditEventId,
        boolean restorable
) {
}
