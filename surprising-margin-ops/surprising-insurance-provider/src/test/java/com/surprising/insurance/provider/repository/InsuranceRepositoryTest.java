package com.surprising.insurance.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.LiquidationFeeSettledEvent;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InsuranceRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void duplicateFundAdjustmentDoesNotUpdateBalanceAgain() throws Exception {
        InsuranceRepository repository = new InsuranceRepository(jdbcTemplate);

        when(jdbcTemplate.queryForObject(contains("SELECT balance_units"), eq(Long.class),
                eq("USDT_PERPETUAL"), eq("USDT")))
                .thenReturn(1_000L);
        when(jdbcTemplate.queryForObject(contains("INSERT INTO insurance_sequences"), eq(Long.class),
                eq("insurance-ledger"))).thenReturn(101L);
        when(jdbcTemplate.update(contains("INSERT INTO insurance_fund_ledger"), any(Object[].class)))
                .thenReturn(0);
        when(jdbcTemplate.query(contains("FROM insurance_fund_ledger"), anyRowMapper(), eq("ops-1"),
                eq("USDT_PERPETUAL"), eq("USDT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("amount_units")).thenReturn(500L);
                    when(rs.getString("reason")).thenReturn("REPLAYED_REQUEST");
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.query(contains("FROM insurance_fund_balances"), anyRowMapper(),
                eq("USDT_PERPETUAL"), eq("USDT"), eq("USDT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getLong("balance_units")).thenReturn(1_000L);
                    when(rs.getTimestamp("updated_at")).thenReturn(java.sql.Timestamp.from(
                            java.time.Instant.parse("2026-07-01T00:00:00Z")));
                    return List.of(mapper.mapRow(rs, 0));
                });

        var response = repository.adjustFund("USDT", 500L, "ops-1", "REPLAYED_REQUEST");

        assertThat(response.balanceUnits()).isEqualTo(1_000L);
        verify(jdbcTemplate, never()).update(contains("UPDATE insurance_fund_balances"), any(Object[].class));
    }

    @Test
    void duplicateFundAdjustmentWithDifferentPayloadFailsBeforeBalanceMutation() throws Exception {
        InsuranceRepository repository = new InsuranceRepository(jdbcTemplate);

        when(jdbcTemplate.queryForObject(contains("SELECT balance_units"), eq(Long.class),
                eq("USDT_PERPETUAL"), eq("USDT")))
                .thenReturn(1_000L);
        when(jdbcTemplate.queryForObject(contains("INSERT INTO insurance_sequences"), eq(Long.class),
                eq("insurance-ledger"))).thenReturn(101L);
        when(jdbcTemplate.update(contains("INSERT INTO insurance_fund_ledger"), any(Object[].class)))
                .thenReturn(0);
        when(jdbcTemplate.query(contains("FROM insurance_fund_ledger"), anyRowMapper(), eq("ops-1"),
                eq("USDT_PERPETUAL"), eq("USDT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("amount_units")).thenReturn(500L);
                    when(rs.getString("reason")).thenReturn("INITIAL_FUND");
                    return List.of(mapper.mapRow(rs, 0));
                });

        assertThatThrownBy(() -> repository.adjustFund("USDT", 600L, "ops-1", "INITIAL_FUND"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting duplicate insurance fund reference");
        verify(jdbcTemplate, never()).update(contains("UPDATE insurance_fund_balances"), any(Object[].class));
    }

    @Test
    void coverageFailsWhenDurableAccountCommandCannotBeEnqueued() throws Exception {
        InsuranceRepository repository = new InsuranceRepository(
                jdbcTemplate, new InsuranceProperties(), new ObjectMapper());

        when(jdbcTemplate.query(contains("FROM account_deficits"), anyRowMapper(), eq("USDT_PERPETUAL"), eq(1)))
                .thenAnswer(deficitRow("USDT_PERPETUAL", 1001L, "USDT", 500L));
        when(jdbcTemplate.queryForObject(contains("SELECT balance_units, reserved_units"), anyRowMapper(),
                eq("USDT_PERPETUAL"), eq("USDT"))).thenAnswer(fundBalance(1_000L, 0L));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO insurance_sequences"), eq(Long.class),
                eq("insurance-coverage"))).thenReturn(201L);
        when(jdbcTemplate.update(contains("UPDATE insurance_fund_balances"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO insurance_deficit_coverages"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO account_outbox_events"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.coverDeficits(1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account command enqueue");

        verify(jdbcTemplate, never()).update(contains("UPDATE account_deficits"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_ledger_entries"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO insurance_fund_ledger"), any(Object[].class));
    }

    @Test
    void coverDeficitsReservesFundAndEmitsDependentAccountCommands() throws Exception {
        InsuranceProperties properties = new InsuranceProperties();
        InsuranceRepository repository = new InsuranceRepository(jdbcTemplate, properties, new ObjectMapper());

        when(jdbcTemplate.query(contains("FROM account_deficits"), anyRowMapper(), eq("USDT_PERPETUAL"), eq(1)))
                .thenAnswer(deficitRow("USDT_PERPETUAL", 4004L, "USDT", 1_000L));
        when(jdbcTemplate.queryForObject(contains("SELECT balance_units, reserved_units"), anyRowMapper(),
                eq("USDT_PERPETUAL"), eq("USDT"))).thenAnswer(fundBalance(600L, 0L));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO insurance_sequences"), eq(Long.class),
                eq("insurance-coverage"))).thenReturn(9501L);
        when(jdbcTemplate.update(contains("UPDATE insurance_fund_balances"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO insurance_deficit_coverages"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO account_outbox_events"), any(Object[].class)))
                .thenReturn(1);

        int coveredRows = repository.coverDeficits(1);

        assertThat(coveredRows).isEqualTo(1);
        verify(jdbcTemplate).update(contains("INSERT INTO insurance_deficit_coverages"),
                eq(9501L), eq("USDT_PERPETUAL"), eq(4004L), eq("USDT"), eq(1_000L), eq(600L), eq(400L),
                eq("INSURANCE_RESERVE:LINEAR_PERPETUAL:9501"),
                eq("INSURANCE_FINALIZE:LINEAR_PERPETUAL:9501"),
                any(java.sql.Timestamp.class),
                any(java.sql.Timestamp.class));
        verify(jdbcTemplate).update(contains("UPDATE insurance_fund_balances"),
                eq(600L), any(java.sql.Timestamp.class), eq("USDT_PERPETUAL"), eq("USDT"), eq(600L));
        verify(jdbcTemplate, times(2)).update(contains("INSERT INTO account_outbox_events"),
                eq("LINEAR_PERPETUAL"), eq(9501L),
                eq("surprising.linear-perp.account.user.commands.v1"),
                eq("LINEAR_PERPETUAL:4004"), any(String.class), any(String.class),
                any(java.sql.Timestamp.class), any(java.sql.Timestamp.class), any(java.sql.Timestamp.class));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_deficits"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_ledger_entries"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO insurance_fund_ledger"), any(Object[].class));
    }

    @Test
    void productLineModeEmitsProductScopedAccountCommands() throws Exception {
        InsuranceProperties properties = new InsuranceProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        InsuranceRepository repository = new InsuranceRepository(jdbcTemplate, properties, new ObjectMapper());

        when(jdbcTemplate.query(contains("FROM account_product_deficits"), anyRowMapper(),
                eq("USDT_DELIVERY"), eq(1)))
                .thenAnswer(deficitRow("USDT_DELIVERY", 4004L, "USDT", 1_000L));
        when(jdbcTemplate.queryForObject(contains("SELECT balance_units, reserved_units"), anyRowMapper(),
                eq("USDT_DELIVERY"), eq("USDT"))).thenAnswer(fundBalance(600L, 0L));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO insurance_sequences"), eq(Long.class),
                eq("insurance-coverage"))).thenReturn(9501L);
        when(jdbcTemplate.update(contains("UPDATE insurance_fund_balances"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO insurance_deficit_coverages"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO account_outbox_events"), any(Object[].class)))
                .thenReturn(1);

        assertThat(repository.coverDeficits(1)).isEqualTo(1);

        verify(jdbcTemplate, times(2)).update(contains("INSERT INTO account_outbox_events"),
                eq("LINEAR_DELIVERY"), eq(9501L),
                eq("surprising.linear-delivery.account.user.commands.v1"),
                eq("LINEAR_DELIVERY:4004"), any(String.class), any(String.class),
                any(java.sql.Timestamp.class), any(java.sql.Timestamp.class), any(java.sql.Timestamp.class));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_product_deficits"), any(Object[].class));
        verify(jdbcTemplate, never()).update(
                contains("INSERT INTO account_product_ledger_entries"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_deficits"), any(Object[].class));
    }

    @Test
    void coverDeficitsLeavesRowsForFutureScanWhenFundIsEmpty() throws Exception {
        InsuranceRepository repository = new InsuranceRepository(jdbcTemplate);

        when(jdbcTemplate.query(contains("FROM account_deficits"), anyRowMapper(), eq("USDT_PERPETUAL"), eq(1)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("account_type")).thenReturn("USDT_PERPETUAL");
                    when(rs.getLong("user_id")).thenReturn(4004L);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getLong("deficit_units")).thenReturn(1_000L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.queryForObject(contains("SELECT balance_units"), eq(Long.class),
                eq("USDT_PERPETUAL"), eq("USDT")))
                .thenReturn(0L);

        int coveredRows = repository.coverDeficits(1);

        assertThat(coveredRows).isZero();
        verify(jdbcTemplate, never()).update(contains("INSERT INTO insurance_deficit_coverages"),
                any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_deficits"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_ledger_entries"), any(Object[].class));
    }

    @Test
    void collectLiquidationFeeCreditsFundAndWritesIdempotentLedger() {
        InsuranceRepository repository = new InsuranceRepository(jdbcTemplate);
        LiquidationFeeSettledEvent event = liquidationFeeEvent(9201L, 7001L, 70L);

        when(jdbcTemplate.queryForObject(contains("SELECT balance_units"), eq(Long.class),
                eq("USDT_PERPETUAL"), eq("USDT")))
                .thenReturn(1_000L);
        when(jdbcTemplate.queryForObject(contains("INSERT INTO insurance_sequences"), eq(Long.class),
                eq("insurance-ledger"))).thenReturn(9901L);
        when(jdbcTemplate.update(contains("INSERT INTO insurance_fund_ledger"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE insurance_fund_balances"), any(Object[].class)))
                .thenReturn(1);

        boolean collected = repository.collectLiquidationFee(event);

        assertThat(collected).isTrue();
        verify(jdbcTemplate).update(contains("INSERT INTO insurance_fund_ledger"),
                eq(9901L), eq("USDT_PERPETUAL"), eq("USDT"), eq(70L), eq(1_070L), eq("9201:7001"),
                any(Timestamp.class));
        verify(jdbcTemplate).update(contains("UPDATE insurance_fund_balances"),
                eq(1_070L), any(Timestamp.class), eq("USDT_PERPETUAL"), eq("USDT"));
    }

    @Test
    void productLineModeRejectsLiquidationFeeForAnotherAccountType() {
        InsuranceProperties properties = new InsuranceProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        InsuranceRepository repository = new InsuranceRepository(jdbcTemplate, properties);
        LiquidationFeeSettledEvent event = liquidationFeeEvent(9201L, 7001L, "USDT_PERPETUAL", 70L);

        assertThatThrownBy(() -> repository.collectLiquidationFee(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match insurance provider account type USDT_DELIVERY");

        verify(jdbcTemplate, never()).update(contains("INSERT INTO insurance_fund_ledger"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("UPDATE insurance_fund_balances"), any(Object[].class));
    }

    @Test
    void duplicateLiquidationFeeEventDoesNotCreditFundAgain() throws Exception {
        InsuranceRepository repository = new InsuranceRepository(jdbcTemplate);
        LiquidationFeeSettledEvent event = liquidationFeeEvent(9201L, 7001L, 70L);

        when(jdbcTemplate.queryForObject(contains("SELECT balance_units"), eq(Long.class),
                eq("USDT_PERPETUAL"), eq("USDT")))
                .thenReturn(1_000L);
        when(jdbcTemplate.queryForObject(contains("INSERT INTO insurance_sequences"), eq(Long.class),
                eq("insurance-ledger"))).thenReturn(9901L);
        when(jdbcTemplate.update(contains("INSERT INTO insurance_fund_ledger"), any(Object[].class)))
                .thenReturn(0);
        when(jdbcTemplate.query(contains("reference_type = 'LIQUIDATION_FEE'"), anyRowMapper(),
                eq("9201:7001"), eq("USDT_PERPETUAL"), eq("USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("amount_units")).thenReturn(70L);
                    when(rs.getString("reason")).thenReturn("COLLECT_LIQUIDATION_FEE");
                    return List.of(mapper.mapRow(rs, 0));
                });

        boolean collected = repository.collectLiquidationFee(event);

        assertThat(collected).isFalse();
        verify(jdbcTemplate, never()).update(contains("UPDATE insurance_fund_balances"), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }

    private org.mockito.stubbing.Answer<List<?>> deficitRow(
            String accountType,
            long userId,
            String asset,
            long deficitUnits) {
        return invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("account_type")).thenReturn(accountType);
            when(rs.getLong("user_id")).thenReturn(userId);
            when(rs.getString("asset")).thenReturn(asset);
            when(rs.getLong("deficit_units")).thenReturn(deficitUnits);
            return List.of(mapper.mapRow(rs, 0));
        };
    }

    private org.mockito.stubbing.Answer<Object> fundBalance(long balanceUnits, long reservedUnits) {
        return invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong("balance_units")).thenReturn(balanceUnits);
            when(rs.getLong("reserved_units")).thenReturn(reservedUnits);
            return mapper.mapRow(rs, 0);
        };
    }

    private LiquidationFeeSettledEvent liquidationFeeEvent(long tradeId, long orderId, long amountUnits) {
        return liquidationFeeEvent(tradeId, orderId, "USDT_PERPETUAL", amountUnits);
    }

    private LiquidationFeeSettledEvent liquidationFeeEvent(long tradeId,
                                                          long orderId,
                                                          String accountType,
                                                          long amountUnits) {
        return new LiquidationFeeSettledEvent(8801L, tradeId, orderId, 6001L, 9401L, 2002L,
                "BTC-USDT", MarginMode.CROSS, accountType, "USDT", amountUnits, 3_000L,
                Instant.parse("2026-07-01T00:00:00Z"), "trace-1");
    }
}
