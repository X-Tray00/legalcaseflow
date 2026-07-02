package bg.nbu.legalcaseflow.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

@Entity
// COURSEWORK: audit записите са append-only; @Immutable забранява Hibernate update на историята.
@Immutable
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_event_time", columnList = "occurred_at"),
        @Index(name = "idx_audit_event_actor", columnList = "actor_username"),
        @Index(name = "idx_audit_event_resource", columnList = "resource_type,resource_id")
})
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    private Long actorId;

    @Column(name = "actor_username")
    private String actorUsername;

    @Enumerated(EnumType.STRING)
    private Role actorRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditOutcome outcome;

    @Column(nullable = false)
    private String resourceType;

    private Long resourceId;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String beforeState;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String afterState;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String metadata;

    private String requestMethod;

    private String requestPath;

    private String ipAddress;

    @Column(length = 1000)
    private String userAgent;

    private Long sourceAuditEventId;
}
