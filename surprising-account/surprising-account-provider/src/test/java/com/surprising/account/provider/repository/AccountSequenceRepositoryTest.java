package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountSequenceRepositoryTest {

    @Test
    void allocatesIdsFromOneNativeSequenceBlockBeforeRequestingAnother() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountSequenceRepository repository = new AccountSequenceRepository(jdbcTemplate, 3);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq("public.account_ledger_entry_seq")))
                .thenReturn(1L, 2L);

        assertThat(repository.nextSequence("ledger-entry")).isEqualTo(1L);
        assertThat(repository.nextSequence("ledger-entry")).isEqualTo(2L);
        assertThat(repository.nextSequence("ledger-entry")).isEqualTo(3L);
        assertThat(repository.nextSequence("ledger-entry")).isEqualTo(4L);

        verify(jdbcTemplate, times(2)).queryForObject(anyString(), eq(Long.class),
                eq("public.account_ledger_entry_seq"));
    }

    @Test
    void keepsIndependentRangesForDifferentIdentifierDomains() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountSequenceRepository repository = new AccountSequenceRepository(jdbcTemplate, 10);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString())).thenReturn(1L);

        assertThat(repository.nextSequence("ledger-entry")).isEqualTo(1L);
        assertThat(repository.nextSequence("position-event")).isEqualTo(1L);
    }

    @Test
    void rejectsUnknownSequenceNames() {
        AccountSequenceRepository repository = new AccountSequenceRepository(org.mockito.Mockito.mock(JdbcTemplate.class));

        assertThatThrownBy(() -> repository.nextSequence("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported account sequence");
    }
}
