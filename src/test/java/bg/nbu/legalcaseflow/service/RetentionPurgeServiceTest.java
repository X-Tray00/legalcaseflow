package bg.nbu.legalcaseflow.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RetentionPurgeServiceTest {

    @Autowired
    private RetentionPurgeService retentionPurgeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("delete from case_types where name in ('PURGE_OLD', 'PURGE_RECENT')");
    }

    @Test
    void purgesRowsOlderThanRetentionAndKeepsRecentOnes() {
        Timestamp expired = Timestamp.from(Instant.now().minus(40, ChronoUnit.DAYS));
        Timestamp recent = Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS));
        jdbcTemplate.update("insert into case_types(name, description, deleted_at) values (?, ?, ?)",
                "PURGE_OLD", "expired soft-deleted", expired);
        jdbcTemplate.update("insert into case_types(name, description, deleted_at) values (?, ?, ?)",
                "PURGE_RECENT", "within retention window", recent);

        RetentionPurgeService.PurgeResult result = retentionPurgeService.purgeExpired();

        Long oldCount = jdbcTemplate.queryForObject(
                "select count(*) from case_types where name = ?", Long.class, "PURGE_OLD");
        Long recentCount = jdbcTemplate.queryForObject(
                "select count(*) from case_types where name = ?", Long.class, "PURGE_RECENT");

        assertThat(oldCount).isZero();                 // older than 30 days → permanently removed
        assertThat(recentCount).isEqualTo(1L);         // inside the window → kept for restore
        assertThat(result.deleted()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void finalCutoffCheckProtectsRecentlyDeletedRows() {
        Timestamp recent = Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS));
        Timestamp cutoff = Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS));
        jdbcTemplate.update("insert into case_types(name, description, deleted_at) values (?, ?, ?)",
                "PURGE_RECENT", "inside restore window", recent);
        Long id = jdbcTemplate.queryForObject(
                "select id from case_types where name = ?", Long.class, "PURGE_RECENT");

        int deleted = retentionPurgeService.purgeRow("case_types", id, cutoff);

        Long remaining = jdbcTemplate.queryForObject(
                "select count(*) from case_types where id = ?", Long.class, id);
        assertThat(deleted).isZero();
        assertThat(remaining).isEqualTo(1L);
    }
}
