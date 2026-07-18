package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
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

        assertThat(repository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY)).isEqualTo(1L);
        assertThat(repository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY)).isEqualTo(2L);
        assertThat(repository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY)).isEqualTo(3L);
        assertThat(repository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY)).isEqualTo(4L);

        verify(jdbcTemplate, times(2)).queryForObject(anyString(), eq(Long.class),
                eq("public.account_ledger_entry_seq"));
    }

    @Test
    void keepsIndependentRangesForDifferentIdentifierDomains() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountSequenceRepository repository = new AccountSequenceRepository(jdbcTemplate, 10);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString())).thenReturn(1L);

        assertThat(repository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY)).isEqualTo(1L);
        assertThat(repository.nextSequence(AccountSequenceRepository.Sequence.POSITION_EVENT)).isEqualTo(1L);
    }

    @Test
    void allocatesEveryDeclaredIdentifierDomainFromItsConfiguredNativeSequence() {
        JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
        AccountSequenceRepository repository = new AccountSequenceRepository(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), anyString())).thenReturn(1L);

        for (AccountSequenceRepository.Sequence sequence : AccountSequenceRepository.Sequence.values()) {
            assertThat(repository.nextSequence(sequence)).isEqualTo(1L);
            verify(jdbcTemplate).queryForObject(anyString(), eq(Long.class), eq(sequence.databaseSequence()));
        }

        assertThat(AccountSequenceRepository.Sequence.values())
                .extracting(AccountSequenceRepository.Sequence::databaseSequence)
                .doesNotHaveDuplicates();
    }
}
