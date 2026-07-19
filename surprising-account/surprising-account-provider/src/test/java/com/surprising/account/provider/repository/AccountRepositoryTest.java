package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.TradeParticipantRole;
import com.surprising.account.provider.model.LiquidationFeeContext;
import com.surprising.account.provider.model.PositionState;
import com.surprising.account.provider.service.PositionCacheAfterCommitSynchronizer;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private AccountSequenceRepository sequenceRepository;

    @Test
    void completeTradeSideInsertsOneImmutableParticipantRow() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        MatchTradeEvent trade = mock(MatchTradeEvent.class);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        Timestamp timestamp = Timestamp.from(now);
        when(trade.symbol()).thenReturn("BTC-USDT");
        when(trade.tradeId()).thenReturn(55L);
        when(trade.takerUserId()).thenReturn(1001L);
        when(trade.makerUserId()).thenReturn(2002L);
        when(jdbcTemplate.update(contains(
                        "ON CONFLICT (product_line, symbol, trade_id, participant_role) DO NOTHING"),
                eq("LINEAR_PERPETUAL"), eq("BTC-USDT"), eq(55L), eq("TAKER"), eq(1001L), eq(2002L),
                eq("trade-command"), eq(timestamp))).thenReturn(1);

        repository.completeTradeSide(ProductLine.LINEAR_PERPETUAL, trade,
                TradeParticipantRole.TAKER, "trade-command", now);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("LINEAR_PERPETUAL"), eq("BTC-USDT"), eq(55L),
                eq("TAKER"), eq(1001L), eq(2002L), eq("trade-command"), eq(timestamp));
        assertThat(sql.getValue())
                .contains("INSERT INTO account_trade_settlement_sides")
                .contains("participant_role")
                .contains("ON CONFLICT (product_line, symbol, trade_id, participant_role) DO NOTHING")
                .doesNotContain("DO UPDATE");
    }

    @Test
    void completeTradeSideFailsWhenSideWasAlreadyAppliedOrTradeIdentityConflicts() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        MatchTradeEvent trade = mock(MatchTradeEvent.class);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(trade.symbol()).thenReturn("BTC-USDT");
        when(trade.tradeId()).thenReturn(55L);
        when(trade.takerUserId()).thenReturn(1001L);
        when(trade.makerUserId()).thenReturn(2002L);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> repository.completeTradeSide(ProductLine.LINEAR_PERPETUAL, trade,
                TradeParticipantRole.MAKER, "trade-command", now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to complete trade side")
                .hasMessageContaining("MAKER");
    }

    @Test
    void usdtPerpetualProductBalanceReadsLegacyPerpetualBalance() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.query(contains("FROM account_balances b"), anyRowMapper(),
                eq(1001L), eq("USDT"))).thenAnswer(balance(900L, 100L, 1_000L));

        var response = repository.productBalance(1001L, AccountType.USDT_PERPETUAL, "USDT");

        assertThat(response).isPresent();
        assertThat(response.get().accountType()).isEqualTo(AccountType.USDT_PERPETUAL);
        assertThat(response.get().availableUnits()).isEqualTo(900L);
        assertThat(response.get().lockedUnits()).isEqualTo(100L);
        assertThat(response.get().equityUnits()).isEqualTo(1_000L);
        verify(jdbcTemplate, never()).query(contains("FROM account_product_balances"), anyRowMapper(),
                any(Object[].class));
    }

    @Test
    void settlementMarkPriceTicksUsesFreshKafkaCacheValue() {
        LatestMarkPriceCache markPriceCache = mock(LatestMarkPriceCache.class);
        MarkPriceEvent markPrice = mock(MarkPriceEvent.class);
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository, markPriceCache);
        Instant settlementTime = Instant.parse("2026-07-01T08:00:00Z");
        Duration window = Duration.ofMinutes(30);
        when(markPriceCache.requireFresh("BTC-USDT-260626")).thenReturn(markPrice);
        when(markPrice.instrumentVersion()).thenReturn(4L);
        when(markPrice.markPriceTicks()).thenReturn(600_000L);

        long priceTicks = repository.settlementMarkPriceTicks("BTC-USDT-260626", 4L, settlementTime, window);

        assertThat(priceTicks).isEqualTo(600_000L);
        verify(markPriceCache).requireFresh("BTC-USDT-260626");
    }

    @Test
    void settlementMarkPriceUnitsUsesFreshKafkaCacheValue() {
        LatestMarkPriceCache markPriceCache = mock(LatestMarkPriceCache.class);
        MarkPriceEvent markPrice = mock(MarkPriceEvent.class);
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository, markPriceCache);
        Instant settlementTime = Instant.parse("2026-07-01T08:00:00Z");
        Duration window = Duration.ofMinutes(15);
        when(markPriceCache.requireFresh("BTC-USDT")).thenReturn(markPrice);
        when(markPrice.markPriceUnits()).thenReturn(150L);

        long priceUnits = repository.settlementMarkPriceUnits("BTC-USDT", settlementTime, window);

        assertThat(priceUnits).isEqualTo(150L);
        verify(markPriceCache).requireFresh("BTC-USDT");
    }

    @Test
    void duplicateBalanceAdjustmentReturnsCurrentBalanceWhenPayloadMatches() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);

        when(sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY)).thenReturn(1L);
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(0);
        when(jdbcTemplate.query(contains("FROM account_ledger_entries"), anyRowMapper(),
                eq("deposit-1001-1"), eq(1001L), eq("USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("amount_units")).thenReturn(500L);
                    when(rs.getString("reason")).thenReturn("INITIAL_DEPOSIT");
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.query(contains("SELECT b.user_id"), anyRowMapper(),
                eq(1001L), eq("USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getLong("available_units")).thenReturn(1_000L);
                    when(rs.getLong("locked_units")).thenReturn(0L);
                    when(rs.getLong("equity_units")).thenReturn(1_000L);
                    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")));
                    return List.of(mapper.mapRow(rs, 0));
                });

        var response = repository.adjustBalance(1001L, "USDT", 500L,
                "deposit-1001-1", "INITIAL_DEPOSIT");

        assertThat(response.availableUnits()).isEqualTo(1_000L);
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
    }

    @Test
    void duplicateBalanceAdjustmentWithDifferentPayloadFailsBeforeBalanceMutation() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);

        when(sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY)).thenReturn(1L);
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(0);
        when(jdbcTemplate.query(contains("FROM account_ledger_entries"), anyRowMapper(),
                eq("deposit-1001-1"), eq(1001L), eq("USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("amount_units")).thenReturn(500L);
                    when(rs.getString("reason")).thenReturn("INITIAL_DEPOSIT");
                    return List.of(mapper.mapRow(rs, 0));
                });

        assertThatThrownBy(() -> repository.adjustBalance(1001L, "USDT", 600L,
                "deposit-1001-1", "INITIAL_DEPOSIT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting duplicate balance adjustment reference");
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
    }

    @Test
    void updatePositionModeRequiresNoActiveMarginReservations() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        stubMissingPositionMode(1001L);
        stubPositionModeSwitchChecks(false, false, false, false, false, true);

        assertThatThrownBy(() -> repository.updatePositionMode(1001L, PositionMode.HEDGE, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("position mode switch requires no active margin reservations");
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_position_modes"), any(Object[].class));
    }

    @Test
    void updatePositionModeSwitchesWhenAllGuardsAreClear() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        stubMissingPositionMode(1001L);
        stubPositionModeSwitchChecks(false, false, false, false, false, false);
        when(jdbcTemplate.update(contains("INSERT INTO account_position_modes"),
                eq("LINEAR_PERPETUAL"), eq(1001L), eq("HEDGE"), any(Timestamp.class))).thenReturn(1);

        var response = repository.updatePositionMode(1001L, PositionMode.HEDGE, now);

        assertThat(response.positionMode()).isEqualTo(PositionMode.HEDGE);
        assertThat(response.productLine().name()).isEqualTo("LINEAR_PERPETUAL");
        verify(jdbcTemplate).update(contains("INSERT INTO account_position_modes"),
                eq("LINEAR_PERPETUAL"), eq(1001L), eq("HEDGE"), any(Timestamp.class));
    }

    @Test
    void updatePositionModeUsesProductLineScope() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        stubMissingPositionMode(ProductLine.INVERSE_DELIVERY, 1001L);
        stubPositionModeSwitchChecks(ProductLine.INVERSE_DELIVERY,
                false, false, false, false, false, false);
        when(jdbcTemplate.update(contains("INSERT INTO account_position_modes"),
                eq("INVERSE_DELIVERY"), eq(1001L), eq("HEDGE"), any(Timestamp.class))).thenReturn(1);

        var response = repository.updatePositionMode(ProductLine.INVERSE_DELIVERY,
                1001L, PositionMode.HEDGE, now);

        assertThat(response.productLine()).isEqualTo(ProductLine.INVERSE_DELIVERY);
        assertThat(response.positionMode()).isEqualTo(PositionMode.HEDGE);
        verify(jdbcTemplate).update(contains("INSERT INTO account_position_modes"),
                eq("INVERSE_DELIVERY"), eq(1001L), eq("HEDGE"), any(Timestamp.class));
        verify(jdbcTemplate).queryForObject(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("FROM account_positions")
                                && sql.contains("p.product_line = ?")
                                && !sql.contains("JOIN instruments")),
                eq(Boolean.class), eq(1001L), eq("INVERSE_DELIVERY"));
        verify(jdbcTemplate).queryForObject(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("FROM trading_orders")
                                && sql.contains("o.product_line = ?")
                                && !sql.contains("JOIN instruments")),
                eq(Boolean.class), eq(1001L), eq("INVERSE_DELIVERY"));
        verify(jdbcTemplate).queryForObject(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("FROM trading_trigger_orders")
                                && sql.contains("t.product_line = ?")
                                && !sql.contains("JOIN instruments")),
                eq(Boolean.class), eq(1001L), eq("INVERSE_DELIVERY"));
        verify(jdbcTemplate).queryForObject(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("FROM trading_algo_orders")
                                && sql.contains("a.product_line = ?")
                                && !sql.contains("JOIN instruments")),
                eq(Boolean.class), eq(1001L), eq("INVERSE_DELIVERY"));
        verify(jdbcTemplate).queryForObject(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("FROM trading_match_trades")
                                && sql.contains("mt.product_line = ?")
                                && !sql.contains("JOIN instruments")),
                eq(Boolean.class), eq("INVERSE_DELIVERY"), eq(1001L), eq(1001L));
    }

    @Test
    void positionQueryCanScopeByProductLineContractType() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.query(contains("product_line = ?"), anyRowMapper(),
                eq("LINEAR_DELIVERY"), eq(1001L), eq("BTC-USDT-260925"), eq("CROSS"), eq("NET")))
                .thenReturn(List.of());

        repository.position(ProductLine.LINEAR_DELIVERY, 1001L, "BTC-USDT-260925",
                MarginMode.CROSS, PositionSide.NET);

        verify(jdbcTemplate).query(contains("product_line = ?"), anyRowMapper(),
                eq("LINEAR_DELIVERY"), eq(1001L), eq("BTC-USDT-260925"), eq("CROSS"), eq("NET"));
    }

    @Test
    void positionsQueryCanScopeByProductLineContractType() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.query(contains("product_line = ?"), anyRowMapper(),
                eq("INVERSE_PERPETUAL"), eq(1001L), eq("SHORT"), eq("SHORT"))).thenReturn(List.of());

        repository.positions(ProductLine.INVERSE_PERPETUAL, 1001L, PositionSide.SHORT);

        verify(jdbcTemplate).query(contains("product_line = ?"), anyRowMapper(),
                eq("INVERSE_PERPETUAL"), eq(1001L), eq("SHORT"), eq("SHORT"));
    }

    @Test
    void settlementOpenPositionsLockAllVersionsForProductLineAndSymbol() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.query(contains("FOR UPDATE"), anyRowMapper(),
                eq("LINEAR_DELIVERY"), eq("BTC-USDT-260925"))).thenReturn(List.of());

        repository.openPositionsForSettlement(ProductLine.LINEAR_DELIVERY, "BTC-USDT-260925");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq("LINEAR_DELIVERY"), eq("BTC-USDT-260925"));
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .contains("symbol = ?")
                .contains("signed_quantity_steps <> 0")
                .contains("FOR UPDATE")
                .doesNotContain("instrument_version = ?");
    }

    @Test
    void positionMarginQueryCanScopeByProductLineContractType() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.query(contains("p.product_line = ?"), anyRowMapper(),
                eq("OPTION"), eq(1001L), eq("BTC-USDT-260925-70000-C"), eq("ISOLATED"), eq("LONG")))
                .thenReturn(List.of());

        repository.positionMargin(ProductLine.OPTION, 1001L, "BTC-USDT-260925-70000-C",
                MarginMode.ISOLATED, PositionSide.LONG);

        verify(jdbcTemplate).query(contains("p.product_line = ?"), anyRowMapper(),
                eq("OPTION"), eq(1001L), eq("BTC-USDT-260925-70000-C"), eq("ISOLATED"), eq("LONG"));
    }

    @Test
    void positionMarginAdjustmentLocksPositionByProductLineContractType() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.query(contains("FROM account_product_ledger_entries"), anyRowMapper(),
                eq("iso-add-line"), eq(1001L), eq("USDT_DELIVERY"), eq("BTC-USDT-260925")))
                .thenReturn(List.of());
        when(jdbcTemplate.query(contains("p.product_line = ?"), anyRowMapper(),
                eq("LINEAR_DELIVERY"), eq(1001L), eq("BTC-USDT-260925"), eq("NET")))
                .thenReturn(List.of());

        assertThatThrownBy(() -> repository.adjustIsolatedPositionMargin(ProductLine.LINEAR_DELIVERY,
                1001L, "BTC-USDT-260925", 100L, "iso-add-line", "ADD_POSITION_MARGIN",
                Duration.ofSeconds(10), 50_000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("open isolated position not found");

        verify(jdbcTemplate).query(contains("p.product_line = ?"), anyRowMapper(),
                eq("LINEAR_DELIVERY"), eq(1001L), eq("BTC-USDT-260925"), eq("NET"));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_ledger_entries"), any(Object[].class));
    }

    @Test
    void releasePositionMarginUsesProductLineAccountType() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.query(contains("FROM account_position_margins m"), anyRowMapper(),
                eq("COIN_PERPETUAL"), eq("INVERSE_PERPETUAL"), eq(1001L), eq("BTC-USD"),
                eq("ISOLATED"), eq("SHORT"))).thenReturn(List.of());

        repository.releasePositionMargin(ProductLine.INVERSE_PERPETUAL, 1001L, "BTC-USD",
                MarginMode.ISOLATED, 3L, PositionSide.SHORT, 10L,
                Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq("COIN_PERPETUAL"), eq("INVERSE_PERPETUAL"), eq(1001L), eq("BTC-USD"),
                eq("ISOLATED"), eq("SHORT"));
        assertThat(sql.getValue())
                .contains("? AS account_type")
                .contains("m.product_line = ?")
                .doesNotContain("JOIN account_positions")
                .doesNotContain("JOIN instruments");
    }

    @Test
    void productPositionMarginAdjustmentUsesProductBalanceAndLedger() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.PRODUCT_LEDGER_ENTRY)).thenReturn(7101L);
        when(jdbcTemplate.query(contains("FROM account_product_ledger_entries"), anyRowMapper(),
                eq("iso-add-delivery"), eq(1001L), eq("USDT_DELIVERY"), eq("BTC-USDT-260925")))
                .thenReturn(List.of());
        when(jdbcTemplate.query(contains("p.product_line = ?"), anyRowMapper(),
                eq("LINEAR_DELIVERY"), eq(1001L), eq("BTC-USDT-260925"), eq("NET")))
                .thenAnswer(positionTarget("USDT", 7L, 10L));
        when(jdbcTemplate.update(contains("INSERT INTO account_product_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("SELECT margin_units"), anyRowMapper(),
                eq("LINEAR_DELIVERY"), eq(1001L), eq("BTC-USDT-260925"), eq("USDT"), eq("ISOLATED"), eq("NET")))
                .thenAnswer(marginUnits(600L))
                .thenAnswer(marginUnits(700L));
        when(jdbcTemplate.update(contains("UPDATE account_product_balances"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO account_position_margins"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("FROM account_product_balances b"), anyRowMapper(),
                eq(1001L), eq("USDT_DELIVERY"), eq("USDT"))).thenAnswer(productBalance(AccountType.USDT_DELIVERY,
                900L, 700L, 1_600L));
        when(jdbcTemplate.update(contains("UPDATE account_product_ledger_entries"), any(Object[].class)))
                .thenReturn(1);

        var response = repository.adjustIsolatedPositionMargin(ProductLine.LINEAR_DELIVERY,
                1001L, "BTC-USDT-260925", 100L, "iso-add-delivery", "ADD_POSITION_MARGIN",
                Duration.ofSeconds(10), 50_000L);

        assertThat(response.positionMarginUnits()).isEqualTo(700L);
        assertThat(response.availableUnits()).isEqualTo(900L);
        assertThat(response.lockedUnits()).isEqualTo(700L);
        verify(jdbcTemplate).update(contains("INSERT INTO account_product_ledger_entries"),
                eq(7101L), eq(1001L), eq("USDT_DELIVERY"), eq("USDT"), eq(100L),
                eq("iso-add-delivery"), eq("ADD_POSITION_MARGIN"), eq("BTC-USDT-260925"), any(Timestamp.class));
        verify(jdbcTemplate).update(contains("UPDATE account_product_balances"),
                eq(100L), eq(100L), any(Timestamp.class), eq("USDT_DELIVERY"), eq(1001L), eq("USDT"), eq(100L));
        verify(jdbcTemplate).update(contains("UPDATE account_product_ledger_entries"),
                eq(1_600L), eq("iso-add-delivery"), eq(1001L), eq("USDT_DELIVERY"), eq("USDT"));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_ledger_entries"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
    }

    @Test
    void updatePositionModeRequiresNoOpenPositions() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        stubMissingPositionMode(1001L);
        stubPositionModeSwitchChecks(true, false, false, false, false, false);

        assertThatThrownBy(() -> repository.updatePositionMode(1001L, PositionMode.HEDGE, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("position mode switch requires no open positions");
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_position_modes"), any(Object[].class));
    }

    @Test
    void updatePositionModeRequiresNoActiveOrders() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        stubMissingPositionMode(1001L);
        stubPositionModeSwitchChecks(false, true, false, false, false, false);

        assertThatThrownBy(() -> repository.updatePositionMode(1001L, PositionMode.HEDGE, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("position mode switch requires no active orders");
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_position_modes"), any(Object[].class));
    }

    @Test
    void updatePositionModeRequiresNoPendingTriggerOrders() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        stubMissingPositionMode(1001L);
        stubPositionModeSwitchChecks(false, false, true, false, false, false);

        assertThatThrownBy(() -> repository.updatePositionMode(1001L, PositionMode.HEDGE, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("position mode switch requires no pending trigger orders");
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_position_modes"), any(Object[].class));
    }

    @Test
    void updatePositionModeRequiresAllMatchedTradesSettled() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        stubMissingPositionMode(1001L);
        stubPositionModeSwitchChecks(false, false, false, false, true, false);

        assertThatThrownBy(() -> repository.updatePositionMode(1001L, PositionMode.HEDGE, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("position mode switch requires all matched trades to be settled");
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_position_modes"), any(Object[].class));
    }

    @Test
    void updatePositionModeRequiresNoActiveAlgoOrders() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        stubMissingPositionMode(1001L);
        stubPositionModeSwitchChecks(false, false, false, true, false, false);

        assertThatThrownBy(() -> repository.updatePositionMode(1001L, PositionMode.HEDGE, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("position mode switch requires no active algo orders");
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_position_modes"), any(Object[].class));
    }

    @Test
    void addIsolatedPositionMarginMovesAvailableToLockedAndPositionMargin() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY)).thenReturn(10L);
        when(jdbcTemplate.query(contains("reference_type = 'POSITION_MARGIN_ADJUSTMENT'"), anyRowMapper(),
                eq("iso-add-1"), eq(1001L), eq("BTC-USDT"))).thenReturn(List.of());
        when(jdbcTemplate.query(contains("FROM account_positions p"), anyRowMapper(),
                eq(1001L), eq("BTC-USDT"), eq("NET"))).thenAnswer(positionTarget("USDT", 7L, 10L));
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("SELECT margin_units"), anyRowMapper(),
                eq("LINEAR_PERPETUAL"), eq(1001L), eq("BTC-USDT"), eq("USDT"), eq("ISOLATED"), eq("NET")))
                .thenAnswer(marginUnits(600L))
                .thenAnswer(marginUnits(700L));
        when(jdbcTemplate.update(contains("available_units = available_units -"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO account_position_margins"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("SELECT b.user_id"), anyRowMapper(),
                eq(1001L), eq("USDT"))).thenAnswer(balance(900L, 700L, 1_600L));
        when(jdbcTemplate.update(contains("UPDATE account_ledger_entries"), any(Object[].class)))
                .thenReturn(1);

        var response = repository.adjustIsolatedPositionMargin(1001L, "BTC-USDT", 100L,
                "iso-add-1", "ADD_POSITION_MARGIN", Duration.ofSeconds(10), 50_000L);

        assertThat(response.positionMarginUnits()).isEqualTo(700L);
        assertThat(response.availableUnits()).isEqualTo(900L);
        assertThat(response.lockedUnits()).isEqualTo(700L);
        verify(jdbcTemplate).update(contains("available_units = available_units -"),
                eq(100L), eq(100L), any(Timestamp.class), eq(1001L), eq("USDT"), eq(100L));
        verify(jdbcTemplate).update(contains("INSERT INTO account_position_margins"),
                eq("LINEAR_PERPETUAL"), eq(1001L), eq("BTC-USDT"), eq("USDT"), eq("NET"), eq(100L),
                any(Timestamp.class));
        verify(jdbcTemplate).update(contains("UPDATE account_ledger_entries"),
                eq(1_600L), eq("iso-add-1"), eq(1001L), eq("USDT"));
    }

    @Test
    void removeIsolatedPositionMarginRequiresFreshRiskBuffer() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY)).thenReturn(11L);
        when(jdbcTemplate.query(contains("reference_type = 'POSITION_MARGIN_ADJUSTMENT'"), anyRowMapper(),
                eq("iso-remove-1"), eq(1001L), eq("BTC-USDT"))).thenReturn(List.of());
        when(jdbcTemplate.query(contains("FROM account_positions p"), anyRowMapper(),
                eq(1001L), eq("BTC-USDT"), eq("NET"))).thenAnswer(positionTarget("USDT", 7L, 10L));
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("SELECT margin_units"), anyRowMapper(),
                eq("LINEAR_PERPETUAL"), eq(1001L), eq("BTC-USDT"), eq("USDT"), eq("ISOLATED"), eq("NET")))
                .thenAnswer(marginUnits(1_000L))
                .thenAnswer(marginUnits(800L));
        when(jdbcTemplate.query(contains("FROM risk_position_snapshots"), anyRowMapper(),
                eq(1001L), eq("BTC-USDT"), eq("NET"), eq(10_000L))).thenAnswer(riskSnapshot(7L, 10L, 100L,
                500L, "NORMAL"));
        when(jdbcTemplate.update(contains("SET margin_units = margin_units -"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("available_units = available_units +"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("SELECT b.user_id"), anyRowMapper(),
                eq(1001L), eq("USDT"))).thenAnswer(balance(1_200L, 800L, 2_000L));
        when(jdbcTemplate.update(contains("UPDATE account_ledger_entries"), any(Object[].class)))
                .thenReturn(1);

        var response = repository.adjustIsolatedPositionMargin(1001L, "BTC-USDT", -200L,
                "iso-remove-1", "REMOVE_POSITION_MARGIN", Duration.ofSeconds(10), 50_000L);

        assertThat(response.positionMarginUnits()).isEqualTo(800L);
        verify(jdbcTemplate).update(contains("SET margin_units = margin_units -"),
                eq(200L), any(Timestamp.class), eq("LINEAR_PERPETUAL"), eq(1001L), eq("BTC-USDT"), eq("USDT"),
                eq("NET"), eq(200L));
        verify(jdbcTemplate).update(contains("available_units = available_units +"),
                eq(200L), eq(200L), any(Timestamp.class), eq(1001L), eq("USDT"), eq(200L));
    }

    @Test
    void removeIsolatedPositionMarginRejectsUnsafeRiskAfterRemoval() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY)).thenReturn(12L);
        when(jdbcTemplate.query(contains("reference_type = 'POSITION_MARGIN_ADJUSTMENT'"), anyRowMapper(),
                eq("iso-remove-risky"), eq(1001L), eq("BTC-USDT"))).thenReturn(List.of());
        when(jdbcTemplate.query(contains("FROM account_positions p"), anyRowMapper(),
                eq(1001L), eq("BTC-USDT"), eq("NET"))).thenAnswer(positionTarget("USDT", 7L, 10L));
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("SELECT margin_units"), anyRowMapper(),
                eq("LINEAR_PERPETUAL"), eq(1001L), eq("BTC-USDT"), eq("USDT"), eq("ISOLATED"), eq("NET")))
                .thenAnswer(marginUnits(1_000L));
        when(jdbcTemplate.query(contains("FROM risk_position_snapshots"), anyRowMapper(),
                eq(1001L), eq("BTC-USDT"), eq("NET"), eq(10_000L))).thenAnswer(riskSnapshot(7L, 10L, -100L,
                500L, "NORMAL"));

        assertThatThrownBy(() -> repository.adjustIsolatedPositionMargin(1001L, "BTC-USDT", -600L,
                "iso-remove-risky", "REMOVE_POSITION_MARGIN", Duration.ofSeconds(10), 50_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maintenance margin buffer");
        verify(jdbcTemplate, never()).update(contains("SET margin_units = margin_units -"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("available_units = available_units +"), any(Object[].class));
    }

    @Test
    void duplicatePositionMarginAdjustmentReturnsCurrentStateWithoutMutating() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.query(contains("reference_type = 'POSITION_MARGIN_ADJUSTMENT'"), anyRowMapper(),
                eq("iso-add-dup"), eq(1001L), eq("BTC-USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getLong("amount_units")).thenReturn(100L);
                    when(rs.getString("reason")).thenReturn("ADD_POSITION_MARGIN");
                    when(rs.getString("symbol")).thenReturn("BTC-USDT");
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.query(contains("SELECT margin_units"), anyRowMapper(),
                eq("LINEAR_PERPETUAL"), eq(1001L), eq("BTC-USDT"), eq("USDT"), eq("ISOLATED"), eq("NET")))
                .thenAnswer(marginUnits(700L));
        when(jdbcTemplate.query(contains("SELECT b.user_id"), anyRowMapper(),
                eq(1001L), eq("USDT"))).thenAnswer(balance(900L, 700L, 1_600L));

        var response = repository.adjustIsolatedPositionMargin(1001L, "BTC-USDT", 100L,
                "iso-add-dup", "ADD_POSITION_MARGIN", Duration.ofSeconds(10), 50_000L);

        assertThat(response.positionMarginUnits()).isEqualTo(700L);
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_ledger_entries"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
    }

    @Test
    void realizedLossOnlyDebitsPositionMarginLockedCollateral() throws Exception {
        PositionCacheAfterCommitSynchronizer cacheSynchronizer =
                mock(PositionCacheAfterCommitSynchronizer.class);
        AccountRepository repository =
                new AccountRepository(jdbcTemplate, sequenceRepository, null, cacheSynchronizer);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        when(sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY)).thenReturn(1L);
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_position_margins"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_balances"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_deficits"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("FROM account_position_margins"), anyRowMapper(),
                eq("LINEAR_PERPETUAL"), eq(1001L), eq("USDT"), eq("CROSS")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("symbol")).thenReturn("ETH-USDT");
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getString("position_side")).thenReturn("SHORT");
                    when(rs.getLong("margin_units")).thenReturn(50L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.queryForObject(contains("SELECT b.available_units"), anyRowMapper(),
                eq(1001L), eq("USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("available_units")).thenReturn(20L);
                    when(rs.getLong("locked_units")).thenReturn(100L);
                    when(rs.getLong("deficit_units")).thenReturn(0L);
                    return mapper.mapRow(rs, 0);
                });

        repository.settleRealizedPnl(1001L, "USDT", 5001L, 9001L, "BTC-USDT",
                MarginMode.CROSS, -90L, now);

        verify(jdbcTemplate).update(contains("UPDATE account_position_margins"),
                eq(50L), any(Timestamp.class), eq(1001L), eq("ETH-USDT"), eq("USDT"), eq("CROSS"),
                eq("SHORT"), eq("LINEAR_PERPETUAL"), eq(50L));
        verify(cacheSynchronizer).schedule(
                ProductLine.LINEAR_PERPETUAL, 1001L, "ETH-USDT", MarginMode.CROSS, PositionSide.SHORT);
        verify(jdbcTemplate).update(contains("UPDATE account_balances"),
                eq(0L), eq(50L), any(Timestamp.class), eq(1001L), eq("USDT"));
        verify(jdbcTemplate).update(contains("UPDATE account_deficits"),
                eq(20L), any(Timestamp.class), eq(1001L), eq("USDT"));
    }

    @Test
    void tradePnlLedgerConflictFailsBeforeBalanceMutation() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        when(sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY)).thenReturn(1L);
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.settleRealizedPnl(1001L, "USDT", 5001L, 9001L,
                "BTC-USDT", MarginMode.CROSS, 25L, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trade pnl ledger insert");
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_deficits"), any(Object[].class));
    }

    @Test
    void tradeFeeLedgerConflictFailsBeforeBalanceMutation() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        when(sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY)).thenReturn(1L);
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.settleTradeFee(1001L, "USDT", 5001L, 9001L, -25L,
                "TAKER_FEE", 500L, "BTC-USDT", MarginMode.CROSS, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trade fee ledger insert");
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_deficits"), any(Object[].class));
    }

    @Test
    void liquidationFeeContextReadsFrozenAuditRate() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.query(contains("FROM liquidation_orders"), anyRowMapper(),
                eq(5001L), eq(1001L), eq("BTC-USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("liquidation_order_id")).thenReturn(6001L);
                    when(rs.getLong("candidate_id")).thenReturn(9401L);
                    when(rs.getLong("liquidation_fee_rate_ppm")).thenReturn(3_000L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        var context = repository.liquidationFeeContext(5001L, 1001L, "BTC-USDT").orElseThrow();

        assertThat(context.liquidationOrderId()).isEqualTo(6001L);
        assertThat(context.candidateId()).isEqualTo(9401L);
        assertThat(context.feeRatePpm()).isEqualTo(3_000L);
    }

    @Test
    void liquidationFeeCollectionIsCappedAtAvailableCollateralAndAudited() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        LiquidationFeeContext context = new LiquidationFeeContext(6001L, 9401L, 3_000L);

        when(jdbcTemplate.query(contains("reference_type = 'LIQUIDATION_FEE'"), anyRowMapper(),
                eq("9001:5001"), eq(1001L), eq("USDT"))).thenReturn(List.of());
        when(jdbcTemplate.query(contains("FROM account_position_margins"), anyRowMapper(),
                eq("LINEAR_PERPETUAL"), eq(1001L), eq("USDT"), eq("CROSS"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("symbol")).thenReturn("BTC-USDT");
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getLong("margin_units")).thenReturn(50L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.queryForObject(contains("SELECT b.available_units"), anyRowMapper(),
                eq(1001L), eq("USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("available_units")).thenReturn(20L);
                    when(rs.getLong("locked_units")).thenReturn(50L);
                    when(rs.getLong("deficit_units")).thenReturn(0L);
                    return mapper.mapRow(rs, 0);
                });
        when(jdbcTemplate.update(contains("UPDATE account_position_margins"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_balances"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_deficits"), any(Object[].class)))
                .thenReturn(1);
        when(sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY)).thenReturn(7001L);
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(1);

        var settlement = repository.settleLiquidationFee(1001L, "USDT", 5001L, 9001L, "BTC-USDT",
                MarginMode.CROSS, 100L, context, now).orElseThrow();

        assertThat(settlement.collectedFeeUnits()).isEqualTo(70L);
        verify(jdbcTemplate).update(contains("UPDATE account_position_margins"),
                eq(50L), any(Timestamp.class), eq(1001L), eq("BTC-USDT"), eq("USDT"), eq("CROSS"),
                eq("NET"), eq("LINEAR_PERPETUAL"), eq(50L));
        verify(jdbcTemplate).update(contains("UPDATE account_balances"),
                eq(0L), eq(0L), any(Timestamp.class), eq(1001L), eq("USDT"));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_deficits"), any(Object[].class));
        verify(jdbcTemplate).update(contains("INSERT INTO account_ledger_entries"),
                eq(7001L), eq(1001L), eq("USDT"), eq(-70L), eq(0L), eq("9001:5001"),
                eq(9001L), eq(5001L), eq("BTC-USDT"), eq(3_000L), any(Timestamp.class));
    }

    @Test
    void liquidationFeeSettlementDeclaresTransactionBoundary() throws Exception {
        Method method = AccountRepository.class.getMethod("settleLiquidationFee", long.class, String.class,
                long.class, long.class, String.class, MarginMode.class, long.class,
                LiquidationFeeContext.class, Instant.class);

        assertThat(method.getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void updatePositionMaintainsSymbolOpenInterestBySide() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.query(contains("SELECT signed_quantity_steps"), anyRowMapper(),
                eq("LINEAR_PERPETUAL"), eq(1001L), eq("BTC-USDT"), eq("CROSS"), eq("NET"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("signed_quantity_steps")).thenReturn(10L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.update(contains("UPDATE account_positions"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO trading_symbol_open_interest_shards"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("SELECT user_id, symbol, margin_mode"), anyRowMapper(),
                eq(1001L), eq("BTC-USDT"), eq("CROSS"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("symbol")).thenReturn("BTC-USDT");
                    when(rs.getLong("instrument_version")).thenReturn(1L);
                    when(rs.wasNull()).thenReturn(false);
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getLong("signed_quantity_steps")).thenReturn(-4L);
                    when(rs.getLong("entry_price_ticks")).thenReturn(600_000L);
                    when(rs.getLong("realized_pnl_units")).thenReturn(25L);
                    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
                    return List.of(mapper.mapRow(rs, 0));
                });

        var response = repository.updatePosition(1001L, "BTC-USDT", MarginMode.CROSS,
                new PositionState(-4L, 1L, 600_000L, 25L), now);

        assertThat(response.signedQuantitySteps()).isEqualTo(-4L);
        verify(jdbcTemplate).update(contains("WITH updated_position"), any(Object[].class));
    }

    @Test
    void updatePositionWithNegativeOpenInterestDeltaUsesZeroSeedAndGuardedUpdate() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.update(contains("UPDATE account_positions"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO trading_symbol_open_interest_shards"), any(Object[].class)))
                .thenReturn(1);

        var response = repository.updatePosition(1001L, "BTC-USDT", MarginMode.CROSS,
                new PositionState(6L, 1L, 600_000L, 25L), -4L, now);

        assertThat(response.signedQuantitySteps()).isEqualTo(6L);
        assertThat(response.updatedAt()).isEqualTo(now);
        verify(jdbcTemplate, never()).query(contains("SELECT signed_quantity_steps"), anyRowMapper(),
                eq(1001L), eq("BTC-USDT"), eq("CROSS"), eq("NET"));
        verify(jdbcTemplate).update(contains("VALUES (?, ?, ?, 0, 0, ?)"), any(Object[].class));
        verify(jdbcTemplate).update(contains("UPDATE trading_symbol_open_interest_shards AS shard"),
                any(Object[].class));
        verify(jdbcTemplate).update(contains("WITH updated_position"), any(Object[].class));
    }

    @Test
    void tradeFeeLedgerStoresFeeAuditSnapshot() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        when(sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY)).thenReturn(1L);
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_balances"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_deficits"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("FROM account_position_margins"), anyRowMapper(),
                eq(1001L), eq("USDT"), eq("CROSS")))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(contains("SELECT b.available_units"), anyRowMapper(),
                eq(1001L), eq("USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("available_units")).thenReturn(100L);
                    when(rs.getLong("locked_units")).thenReturn(0L);
                    when(rs.getLong("deficit_units")).thenReturn(0L);
                    return mapper.mapRow(rs, 0);
                });

        repository.settleTradeFee(1001L, "USDT", 5001L, 9001L, -25L,
                "TAKER_FEE", 500L, "BTC-USDT", MarginMode.CROSS, now);

        verify(jdbcTemplate).update(contains("INSERT INTO account_ledger_entries"),
                eq(1L), eq(1001L), eq("USDT"), eq(-25L), eq("9001:5001"), eq("TAKER_FEE"),
                eq(9001L), eq(5001L), eq("BTC-USDT"), eq(500L), any(Timestamp.class));
        verify(jdbcTemplate).update(contains("UPDATE account_ledger_entries"),
                eq(75L), eq("9001:5001"), eq(1001L), eq("USDT"));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_deficits"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_position_margins"),
                any(Object[].class));
    }

    @Test
    void tradeFeeUsesAvailableBalanceFastPathWhenCrossMarginCanCoverDebit() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        when(sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.LEDGER_ENTRY)).thenReturn(1L);
        when(jdbcTemplate.query(contains("WITH updated_balance AS"), anyRowMapper(), any(Object[].class)))
                .thenAnswer(balanceAfterUnits(75L));

        repository.settleTradeFee(1001L, "USDT", 5001L, 9001L, -25L,
                "TAKER_FEE", 500L, "BTC-USDT", MarginMode.CROSS, now);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("WITH updated_balance AS")
                .contains("UPDATE account_balances")
                .contains("INSERT INTO account_ledger_entries")
                .contains("RETURNING balance_after_units");
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_ledger_entries"),
                any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_ledger_entries"),
                any(Object[].class));
        verify(jdbcTemplate, never()).query(contains("FROM account_position_margins"), anyRowMapper(),
                any(Object[].class));
        verify(jdbcTemplate, never()).queryForObject(contains("SELECT b.available_units"), anyRowMapper(),
                any(Object[].class));
    }

    @Test
    void coinPerpetualTradeFeeSettlesProductBalanceWithoutLegacyBalanceMutation() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        when(sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.PRODUCT_LEDGER_ENTRY)).thenReturn(7001L);
        when(jdbcTemplate.update(contains("INSERT INTO account_product_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_product_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_product_balances"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("FROM account_position_margins"), anyRowMapper(),
                eq(1001L), eq("BTC"), eq("CROSS"))).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(contains("FROM account_product_balances b"), anyRowMapper(),
                eq("COIN_PERPETUAL"), eq(1001L), eq("BTC"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("available_units")).thenReturn(100L);
                    when(rs.getLong("locked_units")).thenReturn(0L);
                    when(rs.getLong("deficit_units")).thenReturn(0L);
                    return mapper.mapRow(rs, 0);
                });

        repository.settleTradeFee(AccountType.COIN_PERPETUAL, 1001L, "BTC", 5001L, 9001L, -25L,
                "TAKER_FEE", 500L, "BTC-USD", MarginMode.CROSS, now);

        verify(jdbcTemplate).update(contains("INSERT INTO account_product_ledger_entries"),
                eq(7001L), eq(1001L), eq("COIN_PERPETUAL"), eq("BTC"), eq(-25L), eq(0L),
                eq("TRADE_FEE"), eq("9001:5001"), eq("TAKER_FEE"), any(Timestamp.class));
        verify(jdbcTemplate).update(contains("UPDATE account_product_balances"),
                eq(75L), eq(0L), any(Timestamp.class), eq("COIN_PERPETUAL"), eq(1001L), eq("BTC"));
        verify(jdbcTemplate).update(contains("UPDATE account_product_ledger_entries"),
                eq(75L), eq("TRADE_FEE"), eq("9001:5001"), eq(1001L), eq("COIN_PERPETUAL"), eq("BTC"));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_ledger_entries"), any(Object[].class));
    }

    @Test
    void coinPerpetualTradeFeeUsesProductAvailableBalanceFastPath() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        when(sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.PRODUCT_LEDGER_ENTRY)).thenReturn(7001L);
        when(jdbcTemplate.update(contains("INSERT INTO account_product_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_product_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("UPDATE account_product_balances b"), anyRowMapper(),
                eq(-25L), any(Timestamp.class), eq("COIN_PERPETUAL"), eq(1001L), eq("BTC"), eq(-25L)))
                .thenAnswer(balanceAfterUnits(75L));

        repository.settleTradeFee(AccountType.COIN_PERPETUAL, 1001L, "BTC", 5001L, 9001L, -25L,
                "TAKER_FEE", 500L, "BTC-USD", MarginMode.CROSS, now);

        verify(jdbcTemplate).query(contains("UPDATE account_product_balances b"), anyRowMapper(),
                eq(-25L), any(Timestamp.class), eq("COIN_PERPETUAL"), eq(1001L), eq("BTC"), eq(-25L));
        verify(jdbcTemplate).update(contains("UPDATE account_product_ledger_entries"),
                eq(75L), eq("TRADE_FEE"), eq("9001:5001"), eq(1001L), eq("COIN_PERPETUAL"), eq("BTC"));
        verify(jdbcTemplate, never()).query(contains("FROM account_position_margins"), anyRowMapper(),
                any(Object[].class));
        verify(jdbcTemplate, never()).queryForObject(contains("FROM account_product_balances b"), anyRowMapper(),
                any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
    }

    @Test
    void openingFillFailsWhenOrderMarginReservationIsMissing() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(),
                eq(9001L), eq(1001L), eq("BTC-USDT"))).thenReturn(List.of());

        assertThatThrownBy(() -> repository.consumeOrderMargin(ProductLine.LINEAR_PERPETUAL,
                9001L, 1001L, "BTC-USDT", MarginMode.CROSS,
                3L, 30L, 10L, false, false, Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing order margin reservation");
    }

    @Test
    void openingFillFailsWhenOrderMarginConsumptionUpdateIsSkipped() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(),
                eq(9001L), eq(1001L), eq("BTC-USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getLong("reserved_units")).thenReturn(100L);
                    when(rs.getLong("released_units")).thenReturn(0L);
                    when(rs.getLong("position_margin_units")).thenReturn(0L);
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getLong("order_quantity_steps")).thenReturn(10L);
                    when(rs.getBoolean("reduce_only")).thenReturn(false);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.update(contains("UPDATE account_margin_reservations"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.consumeOrderMargin(ProductLine.LINEAR_PERPETUAL,
                9001L, 1001L, "BTC-USDT", MarginMode.CROSS,
                3L, 30L, 10L, false, false, Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order margin consumption");
    }

    @Test
    void openingFillMovesActualMarginAndReleasesPriceImprovementExcess() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(),
                eq(9001L), eq(1001L), eq("BTC-USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getLong("reserved_units")).thenReturn(660L);
                    when(rs.getLong("released_units")).thenReturn(0L);
                    when(rs.getLong("position_margin_units")).thenReturn(0L);
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getLong("order_quantity_steps")).thenReturn(6L);
                    when(rs.getBoolean("reduce_only")).thenReturn(false);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.update(contains("UPDATE account_margin_reservations"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO account_position_margins"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_balances"), any(Object[].class)))
                .thenReturn(1);

        repository.consumeOrderMargin(ProductLine.LINEAR_PERPETUAL,
                9001L, 1001L, "BTC-USDT", MarginMode.CROSS,
                6L, 600L, 6L, false, true, now);

        verify(jdbcTemplate).update(contains("INSERT INTO account_position_margins"),
                eq("LINEAR_PERPETUAL"), eq(1001L), eq("BTC-USDT"), eq("USDT"), eq("CROSS"), eq("NET"),
                eq(600L), any(Timestamp.class));
        verify(jdbcTemplate).update(contains("UPDATE account_balances"),
                eq(60L), eq(60L), any(Timestamp.class), eq(1001L), eq("USDT"), eq(60L));
        verify(jdbcTemplate).update(contains("UPDATE account_margin_reservations"),
                eq(60L), eq(60L), eq(60L), eq("ORDER_PRICE_IMPROVEMENT"), any(Timestamp.class), eq(9001L));
    }

    @Test
    void coinPerpetualOpeningFillReleasesPriceImprovementExcessToProductAccount() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(),
                eq(9001L), eq(1001L), eq("BTC-USD"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("account_type")).thenReturn("COIN_PERPETUAL");
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("asset")).thenReturn("BTC");
                    when(rs.getLong("reserved_units")).thenReturn(660L);
                    when(rs.getLong("released_units")).thenReturn(0L);
                    when(rs.getLong("position_margin_units")).thenReturn(0L);
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getLong("order_quantity_steps")).thenReturn(6L);
                    when(rs.getBoolean("reduce_only")).thenReturn(false);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.update(contains("UPDATE account_margin_reservations"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO account_position_margins"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_product_balances"), any(Object[].class)))
                .thenReturn(1);

        repository.consumeOrderMargin(ProductLine.LINEAR_PERPETUAL,
                9001L, 1001L, "BTC-USD", MarginMode.CROSS,
                6L, 600L, 6L, false, true, now);

        verify(jdbcTemplate).update(contains("INSERT INTO account_position_margins"),
                eq("LINEAR_PERPETUAL"), eq(1001L), eq("BTC-USD"), eq("BTC"), eq("CROSS"), eq("NET"),
                eq(600L), any(Timestamp.class));
        verify(jdbcTemplate).update(contains("UPDATE account_product_balances"),
                eq(60L), eq(60L), any(Timestamp.class), eq("COIN_PERPETUAL"), eq(1001L), eq("BTC"), eq(60L));
        verify(jdbcTemplate).update(contains("UPDATE account_margin_reservations"),
                eq(60L), eq(60L), eq(60L), eq("ORDER_PRICE_IMPROVEMENT"), any(Timestamp.class), eq(9001L));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
    }

    @Test
    void closingFillFailsWhenNonReduceOnlyOrderMarginReservationIsMissing() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(),
                eq(9001L), eq(1001L), eq("BTC-USDT"))).thenReturn(List.of());
        assertThatThrownBy(() -> repository.releaseOrderMargin(9001L, 1001L, "BTC-USDT",
                3L, 10L, false, false, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing order margin reservation for closing fill 9001");
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
        verify(jdbcTemplate, never()).query(contains("trading_orders"), anyRowMapper(), any(Object[].class));
    }

    @Test
    void closingFillAllowsMissingOrderMarginReservationForReduceOnlyOrder() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(),
                eq(9001L), eq(1001L), eq("BTC-USDT"))).thenReturn(List.of());
        repository.releaseOrderMargin(9001L, 1001L, "BTC-USDT",
                3L, 10L, true, true, now);

        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_margin_reservations"), any(Object[].class));
    }

    @Test
    void closingFillRejectsMismatchedReservationSnapshotWithoutQueryingTradingTables() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(),
                eq(9001L), eq(1001L), eq("BTC-USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getLong("reserved_units")).thenReturn(100L);
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getLong("order_quantity_steps")).thenReturn(10L);
                    when(rs.getBoolean("reduce_only")).thenReturn(false);
                    return List.of(mapper.mapRow(rs, 0));
                });

        assertThatThrownBy(() -> repository.releaseOrderMargin(9001L, 1001L, "BTC-USDT",
                3L, 9L, false, false, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order quantity does not match account reservation");
        verify(jdbcTemplate, never()).query(contains("trading_orders"), anyRowMapper(), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }

    private void stubMissingPositionMode(long userId) {
        stubMissingPositionMode(ProductLine.LINEAR_PERPETUAL, userId);
    }

    private void stubMissingPositionMode(ProductLine productLine, long userId) {
        when(jdbcTemplate.query(contains("FROM account_position_modes"), anyRowMapper(),
                eq(productLine.name()), eq(userId)))
                .thenReturn(List.of());
    }

    private void stubPositionModeSwitchChecks(boolean hasOpenPositions,
                                              boolean hasOpenOrders,
                                              boolean hasPendingTriggers,
                                              boolean hasActiveAlgoOrders,
                                              boolean hasUnsettledTrades,
                                              boolean hasActiveReservations) {
        stubPositionModeSwitchChecks(ProductLine.LINEAR_PERPETUAL,
                hasOpenPositions, hasOpenOrders, hasPendingTriggers, hasActiveAlgoOrders,
                hasUnsettledTrades, hasActiveReservations);
    }

    private void stubPositionModeSwitchChecks(ProductLine productLine,
                                              boolean hasOpenPositions,
                                              boolean hasOpenOrders,
                                              boolean hasPendingTriggers,
                                              boolean hasActiveAlgoOrders,
                                              boolean hasUnsettledTrades,
                                              boolean hasActiveReservations) {
        when(jdbcTemplate.queryForObject(contains("FROM account_positions"), eq(Boolean.class),
                eq(1001L), eq(productLine.name())))
                .thenReturn(hasOpenPositions);
        when(jdbcTemplate.queryForObject(contains("FROM trading_orders"), eq(Boolean.class),
                eq(1001L), eq(productLine.name())))
                .thenReturn(hasOpenOrders);
        when(jdbcTemplate.queryForObject(contains("FROM trading_trigger_orders"), eq(Boolean.class),
                eq(1001L), eq(productLine.name())))
                .thenReturn(hasPendingTriggers);
        when(jdbcTemplate.queryForObject(contains("FROM trading_algo_orders"), eq(Boolean.class),
                eq(1001L), eq(productLine.name())))
                .thenReturn(hasActiveAlgoOrders);
        when(jdbcTemplate.queryForObject(contains("FROM trading_match_trades"), eq(Boolean.class),
                eq(productLine.name()), eq(1001L), eq(1001L)))
                .thenReturn(hasUnsettledTrades);
        when(jdbcTemplate.queryForObject(contains("FROM account_margin_reservations"), eq(Boolean.class),
                eq(1001L), eq(productLine.accountTypeCode()))).thenReturn(hasActiveReservations);
    }

    private org.mockito.stubbing.Answer<List<Object>> positionTarget(String asset,
                                                                     long instrumentVersion,
                                                                     long signedQuantitySteps) {
        return invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("asset")).thenReturn(asset);
            when(rs.getLong("instrument_version")).thenReturn(instrumentVersion);
            when(rs.getLong("signed_quantity_steps")).thenReturn(signedQuantitySteps);
            return List.of(mapper.mapRow(rs, 0));
        };
    }

    private org.mockito.stubbing.Answer<List<Object>> marginUnits(long marginUnits) {
        return invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong("margin_units")).thenReturn(marginUnits);
            return List.of(mapper.mapRow(rs, 0));
        };
    }

    private org.mockito.stubbing.Answer<List<Object>> riskSnapshot(long instrumentVersion,
                                                                   long signedQuantitySteps,
                                                                   long unrealizedPnlUnits,
                                                                   long maintenanceMarginUnits,
                                                                   String status) {
        return invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong("instrument_version")).thenReturn(instrumentVersion);
            when(rs.getLong("signed_quantity_steps")).thenReturn(signedQuantitySteps);
            when(rs.getLong("unrealized_pnl_units")).thenReturn(unrealizedPnlUnits);
            when(rs.getLong("maintenance_margin_units")).thenReturn(maintenanceMarginUnits);
            when(rs.getString("status")).thenReturn(status);
            when(rs.getTimestamp("event_time")).thenReturn(Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")));
            return List.of(mapper.mapRow(rs, 0));
        };
    }

    private org.mockito.stubbing.Answer<List<Object>> balanceAfterUnits(long balanceAfterUnits) {
        return invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong("balance_after_units")).thenReturn(balanceAfterUnits);
            return List.of(mapper.mapRow(rs, 0));
        };
    }

    private org.mockito.stubbing.Answer<List<Object>> balance(long availableUnits,
                                                              long lockedUnits,
                                                              long equityUnits) {
        return invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong("user_id")).thenReturn(1001L);
            when(rs.getString("asset")).thenReturn("USDT");
            when(rs.getLong("available_units")).thenReturn(availableUnits);
            when(rs.getLong("locked_units")).thenReturn(lockedUnits);
            when(rs.getLong("equity_units")).thenReturn(equityUnits);
            when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")));
            return List.of(mapper.mapRow(rs, 0));
        };
    }

    private org.mockito.stubbing.Answer<List<Object>> productBalance(AccountType accountType,
                                                                     long availableUnits,
                                                                     long lockedUnits,
                                                                     long equityUnits) {
        return invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong("user_id")).thenReturn(1001L);
            when(rs.getString("account_type")).thenReturn(accountType.name());
            when(rs.getString("asset")).thenReturn("USDT");
            when(rs.getLong("available_units")).thenReturn(availableUnits);
            when(rs.getLong("locked_units")).thenReturn(lockedUnits);
            when(rs.getLong("equity_units")).thenReturn(equityUnits);
            when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")));
            return List.of(mapper.mapRow(rs, 0));
        };
    }
}
