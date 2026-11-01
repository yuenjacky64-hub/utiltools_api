package com.example.utiltools.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditTrailTest {

    @Test
    @DisplayName("Capacity is bounded: the oldest entries are evicted once capacity is reached")
    void shouldEvictOldestEntriesBeyondCapacity() {
        AuditTrail trail = new AuditTrail(3);

        for (int i = 0; i < 6; i++) {
            trail.record("json-query", CircuitState.CLOSED, CircuitState.OPEN,
                    CircuitTrigger.FAILURE_THRESHOLD_REACHED, "failure " + i);
        }

        assertThat(trail.capacity()).isEqualTo(3);
        assertThat(trail.size()).isEqualTo(3);

        List<AuditEntry> recent = trail.recent(3);
        assertThat(recent).hasSize(3);
        assertThat(recent).extracting(AuditEntry::seq).containsExactly(6L, 5L, 4L);
        assertThat(recent.get(0).reason()).isEqualTo("failure 5");
        assertThat(trail.recent(10)).hasSize(3);
        assertThat(trail.recent(0)).isEmpty();
    }
}
