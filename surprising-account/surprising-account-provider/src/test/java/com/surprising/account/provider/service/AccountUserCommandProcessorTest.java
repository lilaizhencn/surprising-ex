package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountCommandStatus;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.OrderReleaseAccountCommand;
import com.surprising.account.api.model.OrderReservationKind;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.account.api.model.TradeParticipantRole;
import com.surprising.account.api.model.TradeSideSettlementCommand;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.model.AccountCommandRegistration;
import com.surprising.account.provider.model.PendingAccountCommand;
import com.surprising.account.provider.repository.AccountAdlTargetSettlementRepository;
import com.surprising.account.provider.repository.AccountCommandRepository;
import com.surprising.account.provider.repository.AccountDeficitSettlementRepository;
import com.surprising.account.provider.repository.AccountFundingSettlementRepository;
import com.surprising.account.provider.repository.AccountOrderReservationRepository;
import com.surprising.account.provider.repository.AccountOutboxRepository;
import com.surprising.account.provider.repository.AccountRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AccountUserCommandProcessorTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-07-18T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AccountProperties properties = new AccountProperties();
    private final AccountCommandRepository commandRepository = mock(AccountCommandRepository.class);
    private final AccountOutboxRepository outboxRepository = mock(AccountOutboxRepository.class);
    private final AccountAdlTargetSettlementRepository adlRepository =
            mock(AccountAdlTargetSettlementRepository.class);
    private final AccountDeficitSettlementRepository deficitRepository =
            mock(AccountDeficitSettlementRepository.class);
    private final AccountFundingSettlementRepository fundingRepository =
            mock(AccountFundingSettlementRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final AccountOrderReservationRepository reservationRepository =
            mock(AccountOrderReservationRepository.class);
    private final AccountService accountService = mock(AccountService.class);
    private final PositionCacheAfterCommitSynchronizer cacheSynchronizer =
            mock(PositionCacheAfterCommitSynchronizer.class);
    private AccountUserCommandProcessor processor;

    @BeforeEach
    void setUp() {
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        processor = new AccountUserCommandProcessor(
                objectMapper, properties, commandRepository, outboxRepository, adlRepository,
                deficitRepository, fundingRepository, accountRepository, reservationRepository,
                accountService, cacheSynchronizer);
    }

    @Test
    void terminalDuplicateDoesNotApplyFundsOrPublishAnotherResult() {
        AccountUserCommand command = reserveCommand("order-reserve:9001", null);
        when(commandRepository.register(eq(command), any(), any()))
                .thenReturn(AccountCommandRegistration.ALREADY_TERMINAL);

        var outcome = processor.process(command, objectMapper.writeValueAsString(command));

        assertThat(outcome).isEqualTo(AccountUserCommandProcessor.ProcessingOutcome.DUPLICATE);
        verifyNoInteractions(reservationRepository, outboxRepository);
        verify(commandRepository, never()).markApplied(any(), any(), any());
        verify(commandRepository, never()).markRejected(any(), any(), any(), any(), any());
    }

    @Test
    void missingDependencyWaitsWithoutApplyingFunds() {
        AccountUserCommand command = reserveCommand("order-reserve:9002", "funds-transfer:1001");
        when(commandRepository.register(eq(command), any(), any()))
                .thenReturn(AccountCommandRegistration.WAITING_DEPENDENCY);

        var outcome = processor.process(command, objectMapper.writeValueAsString(command));

        assertThat(outcome).isEqualTo(AccountUserCommandProcessor.ProcessingOutcome.WAITING_DEPENDENCY);
        verifyNoInteractions(reservationRepository, outboxRepository);
    }

    @Test
    void rejectedDependencyRejectsDependentAndRequeuesItsWaitingChildren() {
        AccountUserCommand command = reserveCommand("order-reserve:9003", "funds-transfer:1001");
        PendingAccountCommand child = new PendingAccountCommand(
                "order-release:9003", command.partitionKey(), "{\"commandId\":\"order-release:9003\"}");
        when(commandRepository.register(eq(command), any(), any()))
                .thenReturn(AccountCommandRegistration.DEPENDENCY_REJECTED);
        when(commandRepository.waitingDependents(command.commandId())).thenReturn(List.of(child));

        var outcome = processor.process(command, objectMapper.writeValueAsString(command));

        assertThat(outcome).isEqualTo(AccountUserCommandProcessor.ProcessingOutcome.REJECTED);
        verifyNoInteractions(reservationRepository);
        verify(commandRepository).markRejected(eq(command.commandId()), eq(null),
                eq("DEPENDENCY_REJECTED"), any(), any());
        verify(outboxRepository).enqueueCommandResult(
                eq(properties.getKafka().getCommandResultsTopic()), eq(command),
                eq(AccountCommandStatus.REJECTED), eq(null), eq("DEPENDENCY_REJECTED"), any(), any());
        verify(outboxRepository).enqueueUserCommandRetry(
                eq(properties.getKafka().getUserCommandsTopic()), eq(child.partitionKey()),
                eq(child.serializedEnvelope()), any());
    }

    @Test
    void successfulReservationIsAppliedOnceAndRequeuesDependentCommand() {
        AccountUserCommand command = reserveCommand("order-reserve:9004", null);
        PendingAccountCommand child = new PendingAccountCommand(
                "order-release:9004", command.partitionKey(), "{\"commandId\":\"order-release:9004\"}");
        when(commandRepository.register(eq(command), any(), any()))
                .thenReturn(AccountCommandRegistration.READY);
        when(reservationRepository.reserve(eq(ProductLine.LINEAR_PERPETUAL), eq(1001L), any(), any()))
                .thenReturn(true);
        when(commandRepository.waitingDependents(command.commandId())).thenReturn(List.of(child));

        var outcome = processor.process(command, objectMapper.writeValueAsString(command));

        assertThat(outcome).isEqualTo(AccountUserCommandProcessor.ProcessingOutcome.APPLIED);
        verify(reservationRepository).reserve(
                eq(ProductLine.LINEAR_PERPETUAL), eq(1001L), any(), any());
        verify(commandRepository).markApplied(eq(command.commandId()), any(), any());
        verify(outboxRepository).enqueueCommandResult(
                eq(properties.getKafka().getCommandResultsTopic()), eq(command),
                eq(AccountCommandStatus.APPLIED), any(), eq(null), eq(null), any());
        verify(outboxRepository).enqueueUserCommandRetry(
                eq(properties.getKafka().getUserCommandsTopic()), eq(child.partitionKey()),
                eq(child.serializedEnvelope()), any());
    }

    @Test
    void insufficientReservationBecomesDurableBusinessRejection() {
        AccountUserCommand command = reserveCommand("order-reserve:9005", null);
        when(commandRepository.register(eq(command), any(), any()))
                .thenReturn(AccountCommandRegistration.READY);
        when(reservationRepository.reserve(eq(ProductLine.LINEAR_PERPETUAL), eq(1001L), any(), any()))
                .thenReturn(false);
        when(commandRepository.waitingDependents(command.commandId())).thenReturn(List.of());

        var outcome = processor.process(command, objectMapper.writeValueAsString(command));

        assertThat(outcome).isEqualTo(AccountUserCommandProcessor.ProcessingOutcome.REJECTED);
        verify(commandRepository).markRejected(eq(command.commandId()), eq(null),
                eq("INSUFFICIENT_AVAILABLE_BALANCE"), any(), any());
        verify(outboxRepository).enqueueCommandResult(
                eq(properties.getKafka().getCommandResultsTopic()), eq(command),
                eq(AccountCommandStatus.REJECTED), eq(null),
                eq("INSUFFICIENT_AVAILABLE_BALANCE"), any(), any());
        verify(commandRepository, never()).markApplied(any(), any(), any());
    }

    @Test
    void tradeSettlementKeepsDurableCommandAuditWithoutUnusedResultOutbox() {
        AccountUserCommand command = tradeSettlementCommand();
        when(commandRepository.register(eq(command), any(), any()))
                .thenReturn(AccountCommandRegistration.READY);
        when(commandRepository.waitingDependents(command.commandId())).thenReturn(List.of());

        var outcome = processor.process(command, objectMapper.writeValueAsString(command));

        assertThat(outcome).isEqualTo(AccountUserCommandProcessor.ProcessingOutcome.APPLIED);
        verify(accountService).processTradeSide(
                eq(ProductLine.LINEAR_PERPETUAL), eq(command.commandId()), any(TradeSideSettlementCommand.class));
        verify(commandRepository).markApplied(eq(command.commandId()), any(), any());
        verify(outboxRepository, never()).enqueueCommandResult(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void partialOrderReleaseUsesTheMatchingQuantitySnapshot() {
        OrderReleaseAccountCommand payload = new OrderReleaseAccountCommand(
                9007L, false, 10L, 4L, true, "INTERNAL_MARKET_MAKER_SELF_TRADE", OCCURRED_AT);
        AccountUserCommand command = new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                "order-release:9007",
                ProductLine.LINEAR_PERPETUAL,
                1001L,
                AccountUserCommandType.ORDER_RELEASE,
                "MATCHING",
                "9007",
                null,
                objectMapper.writeValueAsString(payload),
                OCCURRED_AT,
                "trace-order-release-9007");
        when(commandRepository.register(eq(command), any(), any()))
                .thenReturn(AccountCommandRegistration.READY);
        when(commandRepository.waitingDependents(command.commandId())).thenReturn(List.of());

        var outcome = processor.process(command, objectMapper.writeValueAsString(command));

        assertThat(outcome).isEqualTo(AccountUserCommandProcessor.ProcessingOutcome.APPLIED);
        verify(reservationRepository).release(
                ProductLine.LINEAR_PERPETUAL, 1001L, 9007L, false, 10L, 4L,
                true, "INTERNAL_MARKET_MAKER_SELF_TRADE", OCCURRED_AT);
    }

    @Test
    void malformedPayloadIsPoisonAndTransactionCannotBeMarkedTerminal() {
        AccountUserCommand command = new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                "order-reserve:9006",
                ProductLine.LINEAR_PERPETUAL,
                1001L,
                AccountUserCommandType.ORDER_RESERVE,
                "ORDER",
                "9006",
                null,
                "{\"orderId\":9006,\"reservedUnits\":-1}",
                OCCURRED_AT,
                "trace-9006");
        when(commandRepository.register(eq(command), any(), any()))
                .thenReturn(AccountCommandRegistration.READY);

        assertThatThrownBy(() -> processor.process(command, objectMapper.writeValueAsString(command)))
                .isInstanceOf(AccountCommandPoisonPillException.class);

        verifyNoInteractions(reservationRepository, outboxRepository);
        verify(commandRepository, never()).markApplied(any(), any(), any());
        verify(commandRepository, never()).markRejected(any(), any(), any(), any(), any());
    }

    private AccountUserCommand reserveCommand(String commandId, String dependency) {
        OrderReserveAccountCommand payload = new OrderReserveAccountCommand(
                Long.parseLong(commandId.substring(commandId.indexOf(':') + 1)),
                "BTC-USDT",
                OrderSide.BUY,
                OrderReservationKind.DERIVATIVE_MARGIN,
                AccountType.USDT_PERPETUAL,
                "USDT",
                MarginMode.CROSS,
                PositionSide.NET,
                100L,
                false,
                500L);
        return new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                commandId,
                ProductLine.LINEAR_PERPETUAL,
                1001L,
                AccountUserCommandType.ORDER_RESERVE,
                "ORDER",
                commandId.substring(commandId.indexOf(':') + 1),
                dependency,
                objectMapper.writeValueAsString(payload),
                OCCURRED_AT,
                "trace-" + commandId);
    }

    private AccountUserCommand tradeSettlementCommand() {
        MatchTradeEvent trade = new MatchTradeEvent(
                8001L, 7001L, "BTC-USDT",
                9001L, 1L, 1001L, OrderSide.BUY,
                9002L, 1L, 2002L,
                100L, 50L, 60_000L, 10L,
                false, false, OCCURRED_AT, "trace-trade-8001");
        TradeSideSettlementCommand payload =
                new TradeSideSettlementCommand(trade, TradeParticipantRole.TAKER, 100L, false);
        return new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                "TRADE_SIDE_SETTLE:LINEAR_PERPETUAL:8001:TAKER:1001",
                ProductLine.LINEAR_PERPETUAL,
                1001L,
                AccountUserCommandType.TRADE_SIDE_SETTLE,
                "MATCHING",
                "LINEAR_PERPETUAL:BTC-USDT:8001",
                null,
                objectMapper.writeValueAsString(payload),
                OCCURRED_AT,
                "trace-trade-8001");
    }
}
