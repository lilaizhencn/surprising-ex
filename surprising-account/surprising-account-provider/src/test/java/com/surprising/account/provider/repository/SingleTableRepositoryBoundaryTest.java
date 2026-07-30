package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SingleTableRepositoryBoundaryTest {

    @Test
    void accountLedgerRepositoryOnlyQueriesAccountLedgerTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        AccountLedgerRepository repository = new AccountLedgerRepository(jdbcTemplate);

        repository.page(null, null, null, 100, null, null);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_ledger_entries")
                .doesNotContain("account_balances")
                .doesNotContain("account_product_ledger_entries");
    }

    @Test
    void productLedgerRepositoryOnlyQueriesProductLedgerTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        ProductLedgerRepository repository = new ProductLedgerRepository(jdbcTemplate);

        repository.page(null, null, null, null, 100, null, null);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_product_ledger_entries")
                .doesNotContain("account_balances")
                .doesNotContain("account_ledger_entries");
    }

    @Test
    void adminBalanceAdjustmentRepositoryOnlyQueriesAdjustmentTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        AdminBalanceAdjustmentRepository repository = new AdminBalanceAdjustmentRepository(jdbcTemplate);

        repository.page(null, null, null, null, null, null, 100, null, null);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_admin_balance_adjustments")
                .doesNotContain("account_balances")
                .doesNotContain("account_ledger_entries");
    }

    @Test
    void productTransferRepositoryOnlyQueriesProductTransferTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        ProductTransferRepository repository = new ProductTransferRepository(jdbcTemplate);

        repository.page(null, null, null, 100, null, null);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_product_transfers")
                .doesNotContain("account_product_balances")
                .doesNotContain("account_product_ledger_entries");
    }

    @Test
    void accountBalanceRepositoryOnlyQueriesAccountBalanceTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        AccountBalanceRepository repository = new AccountBalanceRepository(jdbcTemplate);

        repository.findByUser(1001L);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_balances")
                .doesNotContain("account_deficits")
                .doesNotContain("account_ledger_entries");
    }

    @Test
    void accountDeficitRepositoryOnlyQueriesAccountDeficitTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        AccountDeficitRepository repository = new AccountDeficitRepository(jdbcTemplate);

        repository.findByUser(1001L);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_deficits")
                .doesNotContain("account_balances")
                .doesNotContain("account_ledger_entries");
    }

    @Test
    void productBalanceRepositoryOnlyQueriesProductBalanceTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        ProductBalanceRepository repository = new ProductBalanceRepository(jdbcTemplate);

        repository.findByUser(1001L, null);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_product_balances")
                .doesNotContain("account_product_deficits")
                .doesNotContain("account_product_ledger_entries");
    }

    @Test
    void productDeficitRepositoryOnlyQueriesProductDeficitTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        ProductDeficitRepository repository = new ProductDeficitRepository(jdbcTemplate);

        repository.findByUser(1001L, null);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_product_deficits")
                .doesNotContain("account_product_balances")
                .doesNotContain("account_product_ledger_entries");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JdbcTemplate emptyQueryJdbcTemplate() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        return jdbcTemplate;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String capturedQuery(JdbcTemplate jdbcTemplate) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        return sql.getValue();
    }
}
