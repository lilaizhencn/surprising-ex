package com.surprising.account.provider.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.TradeParticipantRole;
import com.surprising.account.api.model.TradeSideSettlementCommand;
import com.surprising.account.provider.model.AccountCommandTerminalResult;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.AccountTradeSettlementSideRepository;
import com.surprising.account.provider.repository.ProductLedgerRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderSide;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class AccountLedgerProjectionServiceTest {

    @Test
    void projectsOnlyAppliedLocalDeltasAndAllocatesAnIdForEachRow() {
        ProductLedgerRepository ledger = Mockito.mock(ProductLedgerRepository.class);
        AccountSequenceRepository sequences = Mockito.mock(AccountSequenceRepository.class);
        when(sequences.nextProductLedgerEntryId()).thenReturn(701L, 702L);
        AccountLedgerProjectionService service = new AccountLedgerProjectionService(ledger, sequences);
        AccountUserCommand command = new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION, "ledger-command-1", ProductLine.LINEAR_PERPETUAL,
                1001L, AccountUserCommandType.TRADE_SIDE_SETTLE, "MATCHING", "trade-1", null, "{}",
                Instant.parse("2026-08-02T00:00:00Z"), "trace-1");
        Instant projectedAt = Instant.parse("2026-08-02T00:00:05Z");
        AccountCommandTerminalResult terminal = new AccountCommandTerminalResult(
                AccountCommandStatus.APPLIED, "{}", null, null, java.util.List.of(
                        new AccountCommandTerminalResult.LedgerDelta(
                                "USDT", -12L, 988L, "TRADE_SETTLEMENT", "ledger-command-1",
                                "TRADE_SIDE_SETTLE", "BTC-USDT"),
                        new AccountCommandTerminalResult.LedgerDelta(
                                "BTC", 1L, 1L, "TRADE_SETTLEMENT", "ledger-command-1",
                                "TRADE_SIDE_SETTLE", "BTC-USDT")));

        service.project(command, terminal, projectedAt);

        verify(ledger).projectCommandDelta(701L, 1001L, AccountType.USDT_PERPETUAL,
                "USDT", -12L, 988L, "TRADE_SETTLEMENT", "ledger-command-1",
                "TRADE_SIDE_SETTLE", "BTC-USDT", projectedAt);
        verify(ledger).projectCommandDelta(702L, 1001L, AccountType.USDT_PERPETUAL,
                "BTC", 1L, 1L, "TRADE_SETTLEMENT", "ledger-command-1",
                "TRADE_SIDE_SETTLE", "BTC-USDT", projectedAt);
    }

    @Test
    void doesNotProjectRejectedOrEmptyTerminalResults() {
        ProductLedgerRepository ledger = Mockito.mock(ProductLedgerRepository.class);
        AccountSequenceRepository sequences = Mockito.mock(AccountSequenceRepository.class);
        AccountLedgerProjectionService service = new AccountLedgerProjectionService(ledger, sequences);
        AccountUserCommand command = new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION, "ledger-command-rejected", ProductLine.SPOT,
                1001L, AccountUserCommandType.ORDER_RELEASE, "ORDER", "order-1", null, "{}",
                Instant.parse("2026-08-02T00:00:00Z"), "trace-1");

        service.project(command, new AccountCommandTerminalResult(
                AccountCommandStatus.REJECTED, null, "REJECTED", "业务拒绝"), null);

        verify(sequences, never()).nextProductLedgerEntryId();
        verify(ledger, never()).projectCommandDelta(any(Long.class), any(Long.class), any(AccountType.class),
                any(String.class), any(Long.class), any(Long.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(Instant.class));
    }

    @Test
    void projectsTradeSettlementSideFromTerminalResult() throws Exception {
        ProductLedgerRepository ledger = Mockito.mock(ProductLedgerRepository.class);
        AccountSequenceRepository sequences = Mockito.mock(AccountSequenceRepository.class);
        AccountTradeSettlementSideRepository sides = Mockito.mock(AccountTradeSettlementSideRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        MatchTradeEvent trade = new MatchTradeEvent(9L, 10L, "BTC-USDT", 11L, 1L, 1001L,
                OrderSide.BUY, 12L, 1L, 1002L, 10L, 5L, 100_000L, 2L, false, false,
                Instant.parse("2026-08-02T00:00:00Z"), "trace-trade");
        TradeSideSettlementCommand side = new TradeSideSettlementCommand(
                trade, TradeParticipantRole.TAKER, 2L, false, AccountType.USDT_PERPETUAL, "USDT", 100L);
        AccountUserCommand command = new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION, "side-command-1", ProductLine.LINEAR_PERPETUAL,
                1001L, AccountUserCommandType.TRADE_SIDE_SETTLE, "MATCHING", "trade-9", null,
                objectMapper.writeValueAsString(side), trade.eventTime(), trade.traceId());
        Instant projectedAt = Instant.parse("2026-08-02T00:00:05Z");
        AccountLedgerProjectionService service = new AccountLedgerProjectionService(
                ledger, sequences, sides, objectMapper);

        service.project(command, new AccountCommandTerminalResult(
                AccountCommandStatus.APPLIED,
                "{\"orderMarginConsumedUnits\":70,\"orderMarginReleasedUnits\":30}",
                null, null), projectedAt);

        verify(sides).project(eq(command), any(TradeSideSettlementCommand.class), eq(70L), eq(30L), eq(projectedAt));
    }
}
