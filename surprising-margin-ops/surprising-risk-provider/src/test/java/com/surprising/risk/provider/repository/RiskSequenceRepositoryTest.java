package com.surprising.risk.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class RiskSequenceRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void allocatesNativeSequenceValuesInOneDatabaseCall() {
        RiskSequenceRepository repository = new RiskSequenceRepository(jdbcTemplate);
        when(jdbcTemplate.queryForList("SELECT nextval(?::regclass) FROM generate_series(1, ?)", Long.class,
                "risk_liquidation_candidate_id_seq", 3)).thenReturn(List.of(41L, 42L, 43L));

        assertThat(repository.nextSequences("liquidation-candidate", 3)).containsExactly(41L, 42L, 43L);
    }

    @Test
    void zeroCountDoesNotTouchDatabase() {
        RiskSequenceRepository repository = new RiskSequenceRepository(jdbcTemplate);

        assertThat(repository.nextSequences("risk-event", 0)).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void rejectsUnknownSequenceName() {
        RiskSequenceRepository repository = new RiskSequenceRepository(jdbcTemplate);

        assertThatThrownBy(() -> repository.nextSequences("unknown", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown risk sequence");
    }

    @Test
    void failsWhenDatabaseReturnsPartialAllocation() {
        RiskSequenceRepository repository = new RiskSequenceRepository(jdbcTemplate);
        when(jdbcTemplate.queryForList("SELECT nextval(?::regclass) FROM generate_series(1, ?)", Long.class,
                "risk_snapshot_id_seq", 2)).thenReturn(List.of(9L));

        assertThatThrownBy(() -> repository.nextSequences("risk-snapshot", 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allocate 2 values");
    }
}
