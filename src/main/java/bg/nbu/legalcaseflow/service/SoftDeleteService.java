package bg.nbu.legalcaseflow.service;

import bg.nbu.legalcaseflow.domain.AuditAction;
import bg.nbu.legalcaseflow.exception.ConflictException;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

@Service
public class SoftDeleteService {

    private static final Map<String, String> TABLES = Map.of(
            "clients", "clients",
            "lawyers", "lawyers",
            "case-types", "case_types",
            "legal-services", "legal_services",
            "documents", "documents",
            "appointments", "appointments",
            "invoices", "invoices"
    );

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final EntityManager entityManager;

    public SoftDeleteService(JdbcTemplate jdbcTemplate, AuditService auditService, EntityManager entityManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
        this.entityManager = entityManager;
    }

    @Transactional
    public void delete(String resource, Long id) {
        // soft delete пази историческите ID-та и позволява restore; dependency
        // проверката връща 409, вместо да наруши референтната цялост.
        ensureCanDelete(resource, id);
        int updated = jdbcTemplate.update("update " + table(resource) + " set deleted_at = ? where id = ? and deleted_at is null",
                Timestamp.from(Instant.now()), id);
        if (updated == 0) {
            throw new ConflictException("Record is already deleted or does not exist");
        }
        if ("clients".equals(resource) || "lawyers".equals(resource)) {
            String column = "clients".equals(resource) ? "client_id" : "lawyer_id";
            int accounts = jdbcTemplate.update("update users set active = false where " + column + " = ? and active = true", id);
            if (accounts > 0) {
                auditService.record(AuditAction.ACCOUNT_DEACTIVATED, "accounts", id,
                        Map.of("profileType", resource, "active", true),
                        Map.of("profileType", resource, "active", false), null);
            }
        }
        entityManager.flush();
        entityManager.clear();
    }

    @Transactional
    public void restoreDeleted(String resource, Long id) {
        int updated = jdbcTemplate.update("update " + table(resource) + " set deleted_at = null where id = ? and deleted_at is not null", id);
        if (updated == 0) {
            throw new ConflictException("Record is not deleted or does not exist");
        }
        if ("clients".equals(resource) || "lawyers".equals(resource)) {
            String column = "clients".equals(resource) ? "client_id" : "lawyer_id";
            int accounts = jdbcTemplate.update("update users set active = true where " + column + " = ? and active = false", id);
            if (accounts > 0) {
                auditService.record(AuditAction.ACCOUNT_REACTIVATED, "accounts", id,
                        Map.of("profileType", resource, "active", false),
                        Map.of("profileType", resource, "active", true), null);
            }
        }
        entityManager.flush();
        entityManager.clear();
    }

    // проверява дали записът може да се изтрие, ако има зависими записи хвърля ConflictException.
    public void ensureCanDelete(String resource, Long id) {
        long dependencies = switch (resource) {
            case "clients" -> count("select count(*) from legal_services where client_id = ? and deleted_at is null", id)
                    + count("select count(*) from documents where client_id = ? and deleted_at is null", id)
                    + count("select count(*) from appointments where client_id = ? and deleted_at is null", id)
                    + count("select count(*) from chat_conversations where client_id = ?", id);
            case "lawyers" -> count("select count(*) from legal_services where lawyer_id = ? and deleted_at is null", id)
                    + count("select count(*) from documents where lawyer_id = ? and deleted_at is null", id)
                    + count("select count(*) from appointments where lawyer_id = ? and deleted_at is null", id)
                    + count("select count(*) from clients where lead_lawyer_id = ? and deleted_at is null", id)
                    + count("select count(*) from chat_conversations where lawyer_id = ?", id);
            case "case-types" -> count("select count(*) from legal_services where case_type_id = ? and deleted_at is null", id);
            case "legal-services" -> count("select count(*) from invoices where legal_service_id = ? and deleted_at is null", id);
            default -> 0;
        };
        if (dependencies > 0) {
            throw new ConflictException("Record cannot be deleted because it is used by active or historical records");
        }
    }

    private long count(String sql, Long id) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, id);
        return count == null ? 0 : count;
    }

    private String table(String resource) {
        String table = TABLES.get(resource);
        if (table == null) {
            throw new IllegalArgumentException("Unsupported auditable resource: " + resource);
        }
        return table;
    }
}
