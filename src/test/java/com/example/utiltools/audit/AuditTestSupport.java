package com.example.utiltools.audit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Supplies the {@link Clock} the journal needs to measure replay-claim lease deadlines in
 * {@code @WebMvcTest} slices that import {@code AuditJournalService} directly.
 */
@TestConfiguration
public class AuditTestSupport {

    @Bean
    public Clock auditClock() {
        return Clock.systemUTC();
    }
}
