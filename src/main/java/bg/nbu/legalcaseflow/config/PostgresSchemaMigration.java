package bg.nbu.legalcaseflow.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Applies compatibility changes that Hibernate ddl-auto update cannot infer for an existing schema.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PostgresSchemaMigration implements CommandLineRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public PostgresSchemaMigration(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        String database;
        try (Connection connection = dataSource.getConnection()) {
            database = connection.getMetaData().getDatabaseProductName();
        }
        if (!"PostgreSQL".equalsIgnoreCase(database)) {
            return;
        }
        jdbcTemplate.execute("alter table chat_conversations alter column client_id drop not null");
        jdbcTemplate.execute("alter table chat_conversations alter column lawyer_id drop not null");
    }
}
