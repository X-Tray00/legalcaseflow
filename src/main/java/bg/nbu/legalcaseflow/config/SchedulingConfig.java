package bg.nbu.legalcaseflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables @Scheduled jobs (e.g. the retention purge that compacts soft-deleted rows). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
