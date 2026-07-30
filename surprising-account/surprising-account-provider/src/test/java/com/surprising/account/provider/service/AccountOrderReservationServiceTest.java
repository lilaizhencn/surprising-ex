package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.OrderReservationKind;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.account.provider.repository.AccountBalanceRepository;
import com.surprising.account.provider.repository.AccountCommandRepository;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.ProductBalanceRepository;
import com.surprising.account.provider.repository.SpotOrderReservationRepository;
import com.surprising.account.provider.repository.TradeSettlementSideRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountOrderReservationServiceTest {

    private AccountBalanceRepository accountBalanceRepository;
    private ProductBalanceRepository productBalanceRepository;
    private TradeSettlementSideRepository tradeSettlementSideRepository;
    private AccountCommandRepository accountCommandRepository;
    private AccountOrderReservationService service;

    @BeforeEach
    void setUp() {
        AccountSequenceRepository sequenceRepository = mock(AccountSequenceRepository.class);
        accountBalanceRepository = mock(AccountBalanceRepository.class);
        productBalanceRepository = mock(ProductBalanceRepository.class);
        SpotOrderReservationRepository spotReservationRepository = mock(SpotOrderReservationRepository.class);
        tradeSettlementSideRepository = mock(TradeSettlementSideRepository.class);
        accountCommandRepository = mock(AccountCommandRepository.class);
        service = new AccountOrderReservationService(
                sequenceRepository,
                accountBalanceRepository,
                productBalanceRepository,
                spotReservationRepository,
                tradeSettlementSideRepository,
                accountCommandRepository);
    }

    @Test
    void derivativeReservationOnlyMovesAvailableBalanceToLocked() {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        when(accountBalanceRepository.moveAvailableToLocked(
                1001L, "USDT", 100L, now)).thenReturn(true);

        assertThat(service.reserve(
                ProductLine.LINEAR_PERPETUAL, 1001L, derivativeCommand(), now)).isTrue();

        verify(accountBalanceRepository).moveAvailableToLocked(1001L, "USDT", 100L, now);
    }

    @Test
    void terminalReleaseAggregatesSettlementAuditAndCommandResults() {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        when(tradeSettlementSideRepository.marginUsage(9001L))
                .thenReturn(new TradeSettlementSideRepository.MarginUsage(30L, 10L));
        when(accountCommandRepository.releasedOrderMargin(9001L)).thenReturn(5L);
        when(accountBalanceRepository.moveLockedToAvailable(
                1001L, "USDT", 55L, now)).thenReturn(1);

        long released = service.release(
                ProductLine.LINEAR_PERPETUAL, 1001L, 9001L,
                true, 10L, 0L, true, AccountType.USDT_PERPETUAL, "USDT", 100L,
                "ORDER_TERMINAL", now);

        assertThat(released).isEqualTo(55L);
        verify(accountBalanceRepository).moveLockedToAvailable(1001L, "USDT", 55L, now);
    }

    @Test
    void expectedDerivativeReleaseRequiresImmutableSnapshot() {
        assertThatThrownBy(() -> service.release(
                ProductLine.LINEAR_PERPETUAL, 1001L, 9001L,
                true, 10L, 0L, true, null, null, 0L,
                "ORDER_TERMINAL", Instant.parse("2026-07-19T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing derivative reservation snapshot");
    }

    @Test
    void reservationAccountMustMatchCommandProductLine() {
        OrderReserveAccountCommand command = new OrderReserveAccountCommand(
                9001L, "BTC-USDT", OrderSide.BUY, OrderReservationKind.DERIVATIVE_MARGIN,
                AccountType.COIN_PERPETUAL, "BTC", MarginMode.CROSS, PositionSide.NET,
                10L, false, 100L);

        assertThatThrownBy(() -> service.reserve(
                ProductLine.LINEAR_PERPETUAL, 1001L, command,
                Instant.parse("2026-07-19T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match product line");
    }

    private static OrderReserveAccountCommand derivativeCommand() {
        return new OrderReserveAccountCommand(
                9001L, "BTC-USDT", OrderSide.BUY, OrderReservationKind.DERIVATIVE_MARGIN,
                AccountType.USDT_PERPETUAL, "USDT", MarginMode.CROSS, PositionSide.NET,
                10L, false, 100L);
    }
}
