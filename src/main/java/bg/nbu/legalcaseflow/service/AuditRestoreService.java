package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.AuditAction;
import bg.nbu.legalcaseflow.domain.AuditEvent;
import bg.nbu.legalcaseflow.exception.ConflictException;
import bg.nbu.legalcaseflow.web.dto.response.AuditEventResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import bg.nbu.legalcaseflow.websocket.AppChangeEventPublisher;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class AuditRestoreService {

    private final AuditQueryService queryService;
    private final SoftDeleteService softDeleteService;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AppChangeEventPublisher appChangeEventPublisher;
    private final EntityManager entityManager;

    public AuditRestoreService(AuditQueryService queryService,
                               SoftDeleteService softDeleteService,
                               AuditService auditService,
                               JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper,
                               AppChangeEventPublisher appChangeEventPublisher,
                               EntityManager entityManager) {
        this.queryService = queryService;
        this.softDeleteService = softDeleteService;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.appChangeEventPublisher = appChangeEventPublisher;
        this.entityManager = entityManager;
    }

    @Transactional
    public AuditEventResponse restore(Long auditEventId) {
        AuditEvent source = queryService.get(auditEventId);
        if (!queryService.isRestorable(source)) {
            throw new ConflictException("Audit event is stale, already restored, or not restorable");
        }
        Set<Long> affectedInvoiceServiceIds = invoiceServiceIds(source);

        Object beforeRestore;
        Object afterRestore;
        if (source.getAction() == AuditAction.CREATE) {
            beforeRestore = parse(source.getAfterState());
            softDeleteService.delete(source.getResourceType(), source.getResourceId());
            afterRestore = null;
        } else if (source.getAction() == AuditAction.DELETE) {
            beforeRestore = null;
            JsonNode snapshot = parse(source.getBeforeState());
            validateRelations(source.getResourceType(), snapshot);
            softDeleteService.restoreDeleted(source.getResourceType(), source.getResourceId());
            afterRestore = snapshot;
        } else {
            JsonNode current = parse(source.getAfterState());
            beforeRestore = current;
            JsonNode snapshot = parse(source.getBeforeState());
            validateRelations(source.getResourceType(), snapshot);
            restoreAccountState(source.getResourceType(), source.getResourceId(), current, snapshot);
            applySnapshot(source.getResourceType(), source.getResourceId(), snapshot);
            afterRestore = snapshot;
        }

        if ("legal-services".equals(source.getResourceType()) && source.getAction() != AuditAction.CREATE) {
            affectedInvoiceServiceIds.add(source.getResourceId());
        }
        affectedInvoiceServiceIds.forEach(this::syncServicePaymentStatus);
        AuditEvent restored = auditService.recordRestore(source, beforeRestore, afterRestore);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                appChangeEventPublisher.publish(source.getResourceType(), "RESTORED");
            }
        });
        return queryService.findById(restored.getId());
    }

    private Set<Long> invoiceServiceIds(AuditEvent source) {
        Set<Long> ids = new LinkedHashSet<>();
        if (!"invoices".equals(source.getResourceType())) {
            return ids;
        }
        addServiceId(ids, parse(source.getBeforeState()));
        addServiceId(ids, parse(source.getAfterState()));
        return ids;
    }

    private void addServiceId(Set<Long> ids, JsonNode state) {
        JsonNode serviceId = state.path("legalServiceId");
        if (serviceId.canConvertToLong()) {
            ids.add(serviceId.asLong());
        }
    }

    private void syncServicePaymentStatus(Long serviceId) {
        jdbcTemplate.update("""
                update legal_services
                set paid = case when exists (
                    select 1 from invoices
                    where legal_service_id = ? and status = 'PAID' and deleted_at is null
                ) then true else false end
                where id = ? and deleted_at is null
                """, serviceId, serviceId);
        entityManager.clear();
    }

    private void applySnapshot(String resource, Long id, JsonNode state) {
        // COURSEWORK: JdbcTemplate е умишлен тук. Restore работи и с lifecycle/snapshot полета,
        // които не трябва да минават през обичайния CRUD mapper и Hibernate @SQLRestriction.
        int updated = switch (resource) {
            case "clients" -> jdbcTemplate.update("""
                    update clients set full_name=?, identifier=?, contact=?, legal_aid_eligible=?, lead_lawyer_id=?
                    where id=? and deleted_at is null
                    """, text(state, "fullName"), text(state, "identifier"), text(state, "contact"),
                    state.path("legalAidEligible").asBoolean(), nullableLong(state, "leadLawyerId"), id);
            case "lawyers" -> jdbcTemplate.update("""
                    update lawyers set registration_number=?, full_name=?, specialty=?
                    where id=? and deleted_at is null
                    """, text(state, "registrationNumber"), text(state, "fullName"), text(state, "specialty"), id);
            case "case-types" -> jdbcTemplate.update("""
                    update case_types set name=?, description=? where id=? and deleted_at is null
                    """, text(state, "name"), text(state, "description"), id);
            case "legal-services" -> jdbcTemplate.update("""
                    update legal_services set date=?, lawyer_id=?, client_id=?, case_type_id=?, description=?,
                    fee=?, payer=?, paid=? where id=? and deleted_at is null
                    """, date(state, "date"), state.path("lawyerId").asLong(), state.path("clientId").asLong(),
                    state.path("caseTypeId").asLong(), text(state, "description"), decimal(state, "fee"),
                    text(state, "payer"), state.path("paid").asBoolean(), id);
            case "documents" -> jdbcTemplate.update("""
                    update documents set title=?, content=?, client_id=?, lawyer_id=?, issue_date=?, validity_days=?
                    where id=? and deleted_at is null
                    """, text(state, "title"), text(state, "content"), state.path("clientId").asLong(), state.path("lawyerId").asLong(),
                    date(state, "issueDate"), state.path("validityDays").asInt(), id);
            case "appointments" -> jdbcTemplate.update("""
                    update appointments set client_id=?, lawyer_id=?, scheduled_at=?, status=?, topic=?, notes=?
                    where id=? and deleted_at is null
                    """, state.path("clientId").asLong(), state.path("lawyerId").asLong(),
                    timestamp(state, "scheduledAt"), text(state, "status"), text(state, "topic"), text(state, "notes"), id);
            case "invoices" -> jdbcTemplate.update("""
                    update invoices set invoice_number=?, legal_service_id=?, issue_date=?, due_date=?,
                    amount=?, payer=?, status=? where id=? and deleted_at is null
                    """, text(state, "invoiceNumber"), state.path("legalServiceId").asLong(),
                    date(state, "issueDate"), date(state, "dueDate"), decimal(state, "amount"),
                    text(state, "payer"), text(state, "status"), id);
            default -> throw new IllegalArgumentException("Unsupported restorable resource: " + resource);
        };
        if (updated == 0) {
            throw new ConflictException("Record is deleted or no longer exists");
        }
        entityManager.clear();
    }

    private void validateRelations(String resource, JsonNode state) {
        switch (resource) {
            case "clients" -> activeIfPresent("lawyers", nullableLong(state, "leadLawyerId"));
            case "legal-services" -> {
                active("lawyers", state.path("lawyerId").asLong());
                active("clients", state.path("clientId").asLong());
                active("case_types", state.path("caseTypeId").asLong());
            }
            case "documents", "appointments" -> {
                active("clients", state.path("clientId").asLong());
                active("lawyers", state.path("lawyerId").asLong());
            }
            case "invoices" -> active("legal_services", state.path("legalServiceId").asLong());
            default -> {
            }
        }
    }

    private void restoreAccountState(String resource, Long profileId, JsonNode current, JsonNode target) {
        if (!"clients".equals(resource) && !"lawyers".equals(resource)) {
            return;
        }
        String currentUsername = text(current, "username");
        String targetUsername = text(target, "username");
        if (currentUsername != null && targetUsername == null) {
            String column = "clients".equals(resource) ? "client_id" : "lawyer_id";
            int updated = jdbcTemplate.update("update users set active=false where " + column + "=? and active=true", profileId);
            if (updated > 0) {
                auditService.record(AuditAction.ACCOUNT_DEACTIVATED, "accounts", profileId,
                        java.util.Map.of("username", currentUsername, "active", true),
                        java.util.Map.of("username", currentUsername, "active", false),
                        java.util.Map.of("reason", "restore-profile-update"));
            }
        }
    }

    private void activeIfPresent(String table, Long id) {
        if (id != null) active(table, id);
    }

    private void active(String table, Long id) {
        Long count = jdbcTemplate.queryForObject("select count(*) from " + table + " where id=? and deleted_at is null",
                Long.class, id);
        if (count == null || count == 0) {
            throw new ConflictException("Related record is missing or deleted");
        }
    }

    private JsonNode parse(String value) {
        if (value == null) return objectMapper.nullNode();
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid audit snapshot", ex);
        }
    }

    private String text(JsonNode state, String field) {
        JsonNode value = state.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private Long nullableLong(JsonNode state, String field) {
        JsonNode value = state.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asLong();
    }

    private Date date(JsonNode state, String field) {
        return Date.valueOf(LocalDate.parse(text(state, field)));
    }

    private Timestamp timestamp(JsonNode state, String field) {
        return Timestamp.valueOf(LocalDateTime.parse(text(state, field)));
    }

    private BigDecimal decimal(JsonNode state, String field) {
        return new BigDecimal(text(state, field));
    }
}
