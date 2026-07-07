package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.FeeScheduleResponse;
import com.surprising.trading.api.model.FeeScheduleSourceType;
import com.surprising.trading.api.model.FeeScheduleStatus;
import com.surprising.trading.api.model.FeeScheduleUpsertRequest;
import com.surprising.trading.api.model.FeeTierAssignmentResponse;
import com.surprising.trading.api.model.FeeTierQualificationMode;
import com.surprising.trading.api.model.FeeTierQueryResponse;
import com.surprising.trading.api.model.FeeTierResponse;
import com.surprising.trading.api.model.FeeTierUpsertRequest;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.repository.FeeTierRepository;
import com.surprising.trading.order.repository.FeeTierRepository.FeeTierAssignmentRecord;
import com.surprising.trading.order.repository.FeeTierRepository.FeeTierMetrics;
import com.surprising.trading.order.repository.OrderFeeRepository;
import com.surprising.trading.order.repository.OrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FeeTierServiceTest {

    @Test
    void upsertTierReadsBackByTierCode() {
        FeeTierRepository tierRepository = mock(FeeTierRepository.class);
        FeeTierService service = new FeeTierService(tierRepository, mock(OrderFeeRepository.class),
                mock(OrderRepository.class), new TradingOrderProperties());
        FeeTierUpsertRequest request = new FeeTierUpsertRequest("vip7", FeeScheduleSourceType.VIP,
                FeeTierQualificationMode.VOLUME_OR_BALANCE, 1L, 1L, 10L, 100L, 70,
                FeeScheduleStatus.ACTIVE);
        FeeTierResponse tier = tier("VIP7", 10L, 100L, 70);

        when(tierRepository.findTier("vip7")).thenReturn(Optional.of(tier));

        FeeTierResponse response = service.upsertTier(request);

        assertThat(response.tierCode()).isEqualTo("VIP7");
        verify(tierRepository).upsertTier(eq(request), any());
        verify(tierRepository).findTier("vip7");
    }

    @Test
    void queryTiersWithCursorDelegatesToRepositoryPage() {
        FeeTierRepository tierRepository = mock(FeeTierRepository.class);
        FeeTierService service = new FeeTierService(tierRepository, mock(OrderFeeRepository.class),
                mock(OrderRepository.class), new TradingOrderProperties());
        FeeTierQueryResponse expected = new FeeTierQueryResponse(0, List.of(), "next", true,
                "priority.desc", 25);

        when(tierRepository.queryTiersPage(FeeScheduleStatus.ACTIVE, 25, "cursor", "priority.desc"))
                .thenReturn(expected);

        FeeTierQueryResponse response = service.queryTiers(FeeScheduleStatus.ACTIVE, 25,
                "cursor", "priority.desc");

        assertThat(response).isEqualTo(expected);
        verify(tierRepository).queryTiersPage(FeeScheduleStatus.ACTIVE, 25, "cursor", "priority.desc");
    }

    @Test
    void refreshUserTierWritesVipScheduleAndActivatesAssignment() {
        FeeTierRepository tierRepository = mock(FeeTierRepository.class);
        OrderFeeRepository feeRepository = mock(OrderFeeRepository.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        FeeTierService service = new FeeTierService(tierRepository, feeRepository, orderRepository,
                properties);
        FeeTierMetrics metrics = new FeeTierMetrics(50_000_000_000_000L, 1_000_000_000L);
        FeeTierResponse tier = tier("VIP2", 160L, 400L, 20);
        Instant existingTime = Instant.parse("2026-07-01T00:00:00Z");
        FeeTierAssignmentRecord locked = new FeeTierAssignmentRecord(ProductLine.INVERSE_DELIVERY,
                1001L, null, null, 777L,
                0L, 0L, 0L, 0L, FeeScheduleStatus.DISABLED, existingTime, existingTime);
        FeeTierAssignmentResponse persisted = new FeeTierAssignmentResponse(ProductLine.INVERSE_DELIVERY, 1001L, "VIP2",
                FeeScheduleSourceType.VIP, 777L, 160L, 400L, metrics.trailing30dVolumeUnits(),
                metrics.totalAssetBalanceUnits(), FeeScheduleStatus.ACTIVE, existingTime, existingTime);

        when(tierRepository.metrics(eq(ProductLine.INVERSE_DELIVERY), eq(1001L), any())).thenReturn(metrics);
        when(orderRepository.nextSequence("fee-schedule")).thenReturn(777L);
        when(tierRepository.lockAssignment(eq(ProductLine.INVERSE_DELIVERY), eq(1001L), eq(777L), any()))
                .thenReturn(locked);
        when(tierRepository.eligibleTier(metrics.trailing30dVolumeUnits(), metrics.totalAssetBalanceUnits()))
                .thenReturn(Optional.of(tier));
        when(feeRepository.findSchedule(777L, ProductLine.INVERSE_DELIVERY))
                .thenReturn(Optional.of(new FeeScheduleResponse(777L, ProductLine.INVERSE_DELIVERY, 1001L, null,
                        160L, 400L, FeeScheduleSourceType.VIP, "VIP2", "automatic vip fee tier VIP2",
                        FeeScheduleStatus.ACTIVE, existingTime, null, existingTime, existingTime)));
        when(tierRepository.currentAssignment(ProductLine.INVERSE_DELIVERY, 1001L)).thenReturn(Optional.of(persisted));

        FeeTierAssignmentResponse response = service.refreshUserTier(1001L);

        assertThat(response.tierCode()).isEqualTo("VIP2");
        assertThat(response.productLine()).isEqualTo(ProductLine.INVERSE_DELIVERY);
        ArgumentCaptor<FeeScheduleUpsertRequest> scheduleCaptor =
                ArgumentCaptor.forClass(FeeScheduleUpsertRequest.class);
        verify(feeRepository).upsertSchedule(scheduleCaptor.capture(), eq(777L), any());
        assertThat(scheduleCaptor.getValue().productLine()).isEqualTo(ProductLine.INVERSE_DELIVERY);
        assertThat(scheduleCaptor.getValue().sourceType()).isEqualTo(FeeScheduleSourceType.VIP);
        assertThat(scheduleCaptor.getValue().tierCode()).isEqualTo("VIP2");
        assertThat(scheduleCaptor.getValue().makerFeeRatePpm()).isEqualTo(160L);
        verify(tierRepository).activateAssignment(eq(ProductLine.INVERSE_DELIVERY), eq(1001L), eq(tier),
                eq(777L), eq(metrics), any(), any());
    }

    @Test
    void refreshUserTierDisablesPreviousScheduleWhenUserNoLongerQualifies() {
        FeeTierRepository tierRepository = mock(FeeTierRepository.class);
        OrderFeeRepository feeRepository = mock(OrderFeeRepository.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        FeeTierService service = new FeeTierService(tierRepository, feeRepository, orderRepository,
                new TradingOrderProperties());
        FeeTierMetrics metrics = new FeeTierMetrics(0L, 0L);
        Instant existingTime = Instant.parse("2026-07-01T00:00:00Z");
        FeeTierAssignmentRecord locked = new FeeTierAssignmentRecord(1001L, "VIP1", FeeScheduleSourceType.VIP,
                777L, 180L, 450L, 1L, 1L, FeeScheduleStatus.ACTIVE, existingTime, existingTime);
        FeeTierAssignmentResponse disabled = new FeeTierAssignmentResponse(1001L, null,
                null, 777L, 0L, 0L, 0L, 0L, FeeScheduleStatus.DISABLED,
                existingTime, existingTime);

        when(tierRepository.metrics(eq(ProductLine.LINEAR_PERPETUAL), eq(1001L), any())).thenReturn(metrics);
        when(orderRepository.nextSequence("fee-schedule")).thenReturn(778L);
        when(tierRepository.lockAssignment(eq(ProductLine.LINEAR_PERPETUAL), eq(1001L), eq(778L), any()))
                .thenReturn(locked);
        when(tierRepository.eligibleTier(0L, 0L)).thenReturn(Optional.empty());
        when(tierRepository.currentAssignment(ProductLine.LINEAR_PERPETUAL, 1001L)).thenReturn(Optional.of(disabled));

        FeeTierAssignmentResponse response = service.refreshUserTier(1001L);

        assertThat(response.status()).isEqualTo(FeeScheduleStatus.DISABLED);
        assertThat(response.tierCode()).isNull();
        verify(feeRepository).disableSchedule(eq(777L), eq(ProductLine.LINEAR_PERPETUAL), any());
        verify(tierRepository).disableAssignment(eq(ProductLine.LINEAR_PERPETUAL), eq(1001L), eq(metrics), any());
    }

    @Test
    void refreshUserTierUsesAtLeastOneLookbackDay() {
        FeeTierRepository tierRepository = mock(FeeTierRepository.class);
        OrderFeeRepository feeRepository = mock(OrderFeeRepository.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getFeeTier().setLookbackDays(0L);
        FeeTierService service = new FeeTierService(tierRepository, feeRepository, orderRepository, properties);
        FeeTierMetrics metrics = new FeeTierMetrics(0L, 0L);
        Instant existingTime = Instant.parse("2026-07-01T00:00:00Z");
        FeeTierAssignmentRecord locked = new FeeTierAssignmentRecord(1001L, null, null, 777L,
                0L, 0L, 0L, 0L, FeeScheduleStatus.DISABLED, existingTime, existingTime);
        FeeTierAssignmentResponse disabled = new FeeTierAssignmentResponse(1001L, null, null, 777L,
                0L, 0L, 0L, 0L, FeeScheduleStatus.DISABLED, existingTime, existingTime);
        ArgumentCaptor<Instant> sinceCaptor = ArgumentCaptor.forClass(Instant.class);

        when(tierRepository.metrics(eq(ProductLine.LINEAR_PERPETUAL), eq(1001L), sinceCaptor.capture()))
                .thenReturn(metrics);
        when(orderRepository.nextSequence("fee-schedule")).thenReturn(777L);
        when(tierRepository.lockAssignment(eq(ProductLine.LINEAR_PERPETUAL), eq(1001L), eq(777L), any()))
                .thenReturn(locked);
        when(tierRepository.eligibleTier(0L, 0L)).thenReturn(Optional.empty());
        when(tierRepository.currentAssignment(ProductLine.LINEAR_PERPETUAL, 1001L)).thenReturn(Optional.of(disabled));

        Instant before = Instant.now();
        service.refreshUserTier(1001L);
        Instant after = Instant.now();

        assertThat(sinceCaptor.getValue()).isBetween(before.minusSeconds(86_400L), after.minusSeconds(86_400L));
    }

    private FeeTierResponse tier(String code, long makerFeeRatePpm, long takerFeeRatePpm, int priority) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new FeeTierResponse(code, FeeScheduleSourceType.VIP, FeeTierQualificationMode.VOLUME_OR_BALANCE,
                1L, 1L, makerFeeRatePpm, takerFeeRatePpm, priority, FeeScheduleStatus.ACTIVE, now, now);
    }
}
