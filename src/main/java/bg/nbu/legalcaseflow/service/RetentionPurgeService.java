package bg.nbu.legalcaseflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Retention policy for soft-deleted records. Soft delete stays a short "undo" window; this job then
 * permanently removes rows whose {@code deleted_at} is older than {@code app.retention.days}, keeping the
 * main tables compact. The full snapshot of every deletion already lives in the immutable audit log
 * (the real archive), so purged data is not lost — only its restore window closes.
 *
 * <p>Tables are purged child → parent so foreign keys never block a parent before its children are gone.
 * Each row is deleted in its own {@code REQUIRES_NEW} transaction: a row still referenced by a not-yet-expired
 * child fails in isolation and is simply retried on a later run (important on PostgreSQL, where one failed
 * statement would otherwise abort the whole transaction).
 */
@Service
public class RetentionPurgeService {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeService.class);

    /** Child tables first, parents last, so FK constraints are satisfied. */
    private static final List<String> PURGE_ORDER = List.of(
            "invoices", "legal_services", "documents", "appointments", "case_types", "clients", "lawyers");

    private final JdbcTemplate jdbcTemplate;
    private final RetentionPurgeService self;
    private final boolean enabled;
    private final int retentionDays;

    public RetentionPurgeService(JdbcTemplate jdbcTemplate,
                                 @Lazy RetentionPurgeService self,
                                 @Value("${app.retention.enabled:true}") boolean enabled,
                                 @Value("${app.retention.days:30}") int retentionDays) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("app.retention.days must be at least 1");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.self = self;
        this.enabled = enabled;
        this.retentionDays = retentionDays;
    }

    /** Runs daily (default 03:30). Configurable via {@code app.retention.cron}. */
    @Scheduled(cron = "${app.retention.cron:0 30 3 * * *}")
    public void scheduledPurge() {
        if (enabled) {
            purgeExpired();
        }
    }

    /** Permanently removes soft-deleted rows older than the retention window. Returns a summary. */
    public PurgeResult purgeExpired() {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        int deleted = 0;
        int deferred = 0;
        for (String table : PURGE_ORDER) {
            List<Long> ids = jdbcTemplate.queryForList(
                    "select id from " + table + " where deleted_at is not null and deleted_at < ?",
                    Long.class, cutoff);
            for (Long id : ids) {
                try {
                    deleted += self.purgeRow(table, id, cutoff);
                } catch (DataAccessException ex) {
                    deferred++; // still referenced by a not-yet-expired child; retried on a later run
                    log.debug("Retention purge deferred {} #{}: {}", table, id, ex.getMostSpecificCause().getMessage());
                }
            }
        }
        if (deleted > 0 || deferred > 0) {
            log.info("Retention purge (older than {} days): permanently deleted {} rows, deferred {} (still referenced).",
                    retentionDays, deleted, deferred);
        }
        return new PurgeResult(deleted, deferred);
    }

    /**
     * Deletes a single expired row in its own transaction; for profiles, first removes the dead login account.
     * The row is locked and its cutoff is checked again to avoid deleting a concurrently restored/re-deleted record.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeRow(String table, Long id, Timestamp cutoff) {
        if (!PURGE_ORDER.contains(table)) {
            throw new IllegalArgumentException("Unsupported retention table: " + table);
        }
        Objects.requireNonNull(cutoff, "cutoff");
        // COURSEWORK: soft delete gives an undo window; after it expires, this SELECT FOR UPDATE
        // makes the final hard delete race-safe while preserving foreign-key constraints.
        List<Long> expired = jdbcTemplate.queryForList(
                "select id from " + table + " where id = ? and deleted_at is not null and deleted_at < ? for update",
                Long.class, id, cutoff);
        if (expired.isEmpty()) {
            return 0;
        }
        if ("clients".equals(table)) {
            jdbcTemplate.update("delete from users where client_id = ? and active = false", id);
        } else if ("lawyers".equals(table)) {
            jdbcTemplate.update("delete from users where lawyer_id = ? and active = false", id);
        }
        return jdbcTemplate.update(
                "delete from " + table + " where id = ? and deleted_at is not null and deleted_at < ?",
                id, cutoff);
    }

    public record PurgeResult(int deleted, int deferred) {
    }
}
