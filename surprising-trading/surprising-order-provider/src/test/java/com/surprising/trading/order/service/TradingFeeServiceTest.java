package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.FeeScheduleQueryResponse;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleSourceType;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import com.surprising.trading.order.model.OrderFeeSnapshot;
import com.surprising.trading.order.repository.OrderFeeRepository;
import com.surprising.trading.order.repository.OrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TradingFeeServiceTest {

    @Test
    void effectiveFeeUsesCurrentInstrumentVersionWhenVersionIsNotProvided() {
        OrderFeeRepository feeRepository = mock(OrderFeeRepository.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        InstrumentRuleLookup instrumentRuleLookup = mock(InstrumentRuleLookup.class);
        TradingFeeService service = new TradingFeeService(feeRepository, orderRepository, instrumentRuleLookup);

        when(instrumentRuleLookup.currentRule("BTC-USDT")).thenReturn(Optional.of(rule(7L)));
        when(feeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(-50L, 350L, "VIP_SYMBOL")));

        var response = service.effectiveFee(1001L, "btc-usdt", 0L);

        assertThat(response.userId()).isEqualTo(1001L);
        assertThat(response.productLine()).isEqualTo(ProductLine.LINEAR_PERPETUAL);
        assertThat(response.symbol()).isEqualTo("BTC-USDT");
        assertThat(response.instrumentVersion()).isEqualTo(7L);
        assertThat(response.makerFeeRatePpm()).isEqualTo(-50L);
        assertThat(response.takerFeeRatePpm()).isEqualTo(350L);
        assertThat(response.source()).isEqualTo("VIP_SYMBOL");
    }

    @Test
    void effectiveFeeRejectsRequestedProductLineMismatch() {
        OrderFeeRepository feeRepository = mock(OrderFeeRepository.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        InstrumentRuleLookup instrumentRuleLookup = mock(InstrumentRuleLookup.class);
        TradingFeeService service = new TradingFeeService(feeRepository, orderRepository, instrumentRuleLookup);

        when(feeRepository.snapshot(eq(1001L), eq("BTC-USDT"), eq(7L), any()))
                .thenReturn(Optional.of(new OrderFeeSnapshot(ProductLine.INVERSE_PERPETUAL,
                        -50L, 350L, "VIP_SYMBOL")));

        assertThatThrownBy(() -> service.effectiveFee(1001L, "btc-usdt", 7L, ProductLine.LINEAR_PERPETUAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fee schedule unavailable for productLine");
    }

    @Test
    void upsertScheduleAllocatesIdAndReturnsPersistedSchedule() {
        OrderFeeRepository feeRepository = mock(OrderFeeRepository.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        InstrumentRuleLookup instrumentRuleLookup = mock(InstrumentRuleLookup.class);
        TradingFeeService service = new TradingFeeService(feeRepository, orderRepository, instrumentRuleLookup);
        Instant effectiveTime = Instant.parse("2026-07-01T00:00:00Z");
        FeeScheduleUpsertRequest request = new FeeScheduleUpsertRequest(null, 1001L, "BTC-USDT",
                -50L, 350L, FeeScheduleSourceType.VIP, "VIP3", "vip tier",
                FeeScheduleStatus.ACTIVE, effectiveTime, null);
        FeeScheduleResponse persisted = new FeeScheduleResponse(777L, 1001L, "BTC-USDT",
                -50L, 350L, FeeScheduleSourceType.VIP, "VIP3", "vip tier",
                FeeScheduleStatus.ACTIVE, effectiveTime, null, effectiveTime, effectiveTime);

        when(orderRepository.nextSequence("fee-schedule")).thenReturn(777L);
        when(feeRepository.findSchedule(777L)).thenReturn(Optional.of(persisted));

        FeeScheduleResponse response = service.upsertSchedule(request);

        assertThat(response).isEqualTo(persisted);
        verify(feeRepository).upsertSchedule(eq(request), eq(777L), any());
    }

    @Test
    void querySchedulesWithCursorNormalizesSymbolAndDelegatesProductLineToRepositoryPage() {
        OrderFeeRepository feeRepository = mock(OrderFeeRepository.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        InstrumentRuleLookup instrumentRuleLookup = mock(InstrumentRuleLookup.class);
        TradingFeeService service = new TradingFeeService(feeRepository, orderRepository, instrumentRuleLookup);
        FeeScheduleQueryResponse expected = new FeeScheduleQueryResponse(0, List.of(), "next", true,
                "updatedAt.asc", 50);

        when(feeRepository.querySchedulesPage(ProductLine.LINEAR_DELIVERY, 1001L, "BTC-USDT",
                FeeScheduleStatus.ACTIVE, 50, "cursor", "updatedAt.asc")).thenReturn(expected);

        FeeScheduleQueryResponse response = service.querySchedules(ProductLine.LINEAR_DELIVERY, 1001L, "btc-usdt",
                FeeScheduleStatus.ACTIVE, 50, "cursor", "updatedAt.asc");

        assertThat(response).isEqualTo(expected);
        verify(feeRepository).querySchedulesPage(ProductLine.LINEAR_DELIVERY, 1001L, "BTC-USDT",
                FeeScheduleStatus.ACTIVE, 50, "cursor", "updatedAt.asc");
    }

    private InstrumentRule rule(long version) {
        return new InstrumentRule("BTC-USDT", version, "TRADING", ContractType.LINEAR_PERPETUAL,
                Set.of("LIMIT", "MARKET"), Set.of("GTC", "IOC"), true, true, true,
                1L, 100_000L, 1L, 1_000_000_000L, 10_000L, 100_000_000L, 10_000L);
    }
}
