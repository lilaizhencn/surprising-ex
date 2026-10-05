package com.surprising.instrument.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.ContractSettlementMethod;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import com.surprising.instrument.api.model.OptionExerciseStyle;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.instrument.provider.config.InstrumentProperties;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class InstrumentServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().findAndAddModules().build();

    @Test
    void publishesDeliverySettlementToProductTopic() {
        InstrumentOutboxService outboxService = mock(InstrumentOutboxService.class);
        InstrumentService service = service(outboxService, new InstrumentProperties());
        InstrumentResponse instrument = delivery("BTC-USDT-260327", InstrumentStatus.CLOSED);

        service.publishProductLifecycleEvent(instrument, 100_000L, 0L);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).enqueue(eq("INSTRUMENT"), eq(2L),
                eq("surprising.linear-delivery.delivery.settlements.v1"),
                eq("1"), eq("DELIVERY_SETTLEMENT"), event.capture(), any(Instant.class));
        assertThat(event.getValue()).isInstanceOf(DeliverySettlementEvent.class);
        DeliverySettlementEvent deliveryEvent = (DeliverySettlementEvent) event.getValue();
        assertThat(deliveryEvent.instrumentId()).isEqualTo("1");
        assertThat(deliveryEvent.status()).isEqualTo(InstrumentStatus.CLOSED);
    }

    @Test
    void publishesOptionExerciseToProductTopic() {
        InstrumentOutboxService outboxService = mock(InstrumentOutboxService.class);
        InstrumentStorageService storageService = mock(InstrumentStorageService.class);
        when(storageService.latest(1, ProductLine.LINEAR_PERPETUAL))
                .thenReturn(Optional.of(linearPerpetual("BTC-USDT", 2L, 10_000_000L)));
        InstrumentService service = new InstrumentService(storageService, mock(InstrumentValidator.class),
                new InstrumentProperties(), outboxService);
        InstrumentResponse instrument = option("BTC-USDT-260327-50000-C", InstrumentStatus.CLOSED);

        service.publishProductLifecycleEvent(instrument, 0L, 71_000_000L);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).enqueue(eq("INSTRUMENT"), eq(2L),
                eq("surprising.option.option.exercises.v1"),
                eq("1"), eq("OPTION_EXERCISE"), event.capture(), any(Instant.class));
        assertThat(event.getValue()).isInstanceOf(OptionExerciseEvent.class);
        OptionExerciseEvent optionEvent = (OptionExerciseEvent) event.getValue();
        assertThat(optionEvent.underlyingInstrumentId()).isEqualTo("1");
        assertThat(optionEvent.optionType()).isEqualTo(OptionType.CALL);
        assertThat(optionEvent.cashSettlementUnitsPerContract()).isZero();
    }

    @Test
    void usesConfiguredLifecycleTopicOverrides() {
        InstrumentOutboxService outboxService = mock(InstrumentOutboxService.class);
        InstrumentProperties properties = new InstrumentProperties();
        properties.getKafka().setDeliverySettlementsTopic("custom.delivery.settlements");
        InstrumentService service = service(outboxService, properties);

        service.publishProductLifecycleEvent(delivery("BTC-USDT-260327", InstrumentStatus.CLOSED), 100_000L, 0L);

        verify(outboxService).enqueue(eq("INSTRUMENT"), eq(2L), eq("custom.delivery.settlements"),
                eq("1"), eq("DELIVERY_SETTLEMENT"), any(Object.class), any(Instant.class));
    }

    @Test
    void latestAcceptsMatchingProductLine() {
        InstrumentStorageService storageService = mock(InstrumentStorageService.class);
        InstrumentService service = service(storageService);
        InstrumentResponse instrument = option("BTC-USDT-260327-50000-C", InstrumentStatus.TRADING);
        when(storageService.latest(1, ProductLine.OPTION))
                .thenReturn(Optional.of(instrument));

        InstrumentResponse response = service.latest(1, ProductLine.OPTION);

        assertThat(response).isSameAs(instrument);
        verify(storageService).latest(1, ProductLine.OPTION);
    }

    @Test
    void latestRejectsMismatchedProductLine() {
        InstrumentStorageService storageService = mock(InstrumentStorageService.class);
        InstrumentService service = service(storageService);
        when(storageService.latest(1, ProductLine.LINEAR_PERPETUAL))
                .thenReturn(Optional.empty());


        assertThatThrownBy(() -> service.latest(1, ProductLine.LINEAR_PERPETUAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("instrument not found for productLine");
    }

    @Test
    void latestRejectsCorruptProductCurrentVersion() {
        InstrumentStorageService storageService = mock(InstrumentStorageService.class);
        InstrumentService service = service(storageService);
        when(storageService.latest(1, ProductLine.OPTION))
                .thenReturn(Optional.of(delivery("BTC-USDT-260327", InstrumentStatus.TRADING)));

        assertThatThrownBy(() -> service.latest(1, ProductLine.OPTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("instrument product current mismatch");
    }

    @Test
    void settlementConfirmationRejectsInstrumentThatWasNotDrainedToSettling() {
        InstrumentStorageService storageService = mock(InstrumentStorageService.class);
        InstrumentService service = service(storageService);
        when(storageService.latest(1, ProductLine.LINEAR_DELIVERY))
                .thenReturn(Optional.of(delivery("BTC-USDT-260327", InstrumentStatus.TRADING)));

        assertThatThrownBy(() -> service.closeForSettlement(1, ProductLine.LINEAR_DELIVERY,
                100_000L, 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须先进入 SETTLING");
        verify(storageService, org.mockito.Mockito.never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void upsertPublishesCurrentConfigurationWithAuditReference() {
        var storage = mock(InstrumentStorageService.class);
        var outbox = mock(InstrumentOutboxService.class);
        var result = linearPerpetual("BTC-USDT", 4L, 10_000_000L);
        var request = request(result, result.priceTickUnits());
        when(storage.save(eq(result.symbol()), eq(request), eq("admin-1"), eq("maintenance"), any()))
                .thenReturn(result);
        var service = new InstrumentService(storage, mock(InstrumentValidator.class), new InstrumentProperties(), outbox);
        assertThat(service.upsert(request, "admin-1", "maintenance")).isEqualTo(result);
        verify(outbox).enqueue(eq("INSTRUMENT"), eq(4L), eq("surprising.instrument.events.v1"),
                eq("LINEAR_PERPETUAL:1"), eq("UPSERTED"), any(Object.class), any(Instant.class));
    }

    @Test
    void rejectsStaleAdminEditBeforeWritingConfigurationOrOutbox() {
        var storage = mock(InstrumentStorageService.class);
        var outbox = mock(InstrumentOutboxService.class);
        var current = linearPerpetual("BTC-USDT", 4L, 10_000_000L);
        when(storage.latest(1, ProductLine.LINEAR_PERPETUAL)).thenReturn(Optional.of(current));
        var service = new InstrumentService(storage, mock(InstrumentValidator.class), new InstrumentProperties(), outbox);
        assertThatThrownBy(() -> service.edit(request(current, current.priceTickUnits()), "1", "change fee", 3L))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("409");
        verify(storage).lockForUpdate(1, ProductLine.LINEAR_PERPETUAL);
        org.mockito.Mockito.verifyNoInteractions(outbox);
        verify(storage, org.mockito.Mockito.never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void acceptsMatchingAdminEditVersionUnderRowLock() {
        var storage = mock(InstrumentStorageService.class);
        var outbox = mock(InstrumentOutboxService.class);
        var current = linearPerpetual("BTC-USDT", 4L, 10_000_000L);
        var request = request(current, current.priceTickUnits());
        when(storage.latest(1, ProductLine.LINEAR_PERPETUAL)).thenReturn(Optional.of(current));
        when(storage.save(eq(current.symbol()), eq(request), eq("1"), eq("change fee"), any())).thenReturn(current);
        var service = new InstrumentService(storage, mock(InstrumentValidator.class), new InstrumentProperties(), outbox);
        assertThat(service.edit(request, "1", "change fee", 4L)).isEqualTo(current);
        var order = org.mockito.Mockito.inOrder(storage);
        order.verify(storage).lockForUpdate(1, ProductLine.LINEAR_PERPETUAL);
        order.verify(storage).latest(1, ProductLine.LINEAR_PERPETUAL);
        order.verify(storage).save(eq(current.symbol()), eq(request), eq("1"), eq("change fee"), any());
    }

    private InstrumentService service(InstrumentOutboxService outboxService, InstrumentProperties properties) {
        return new InstrumentService(mock(InstrumentStorageService.class), mock(InstrumentValidator.class),
                properties, outboxService);
    }

    private InstrumentService service(InstrumentStorageService storageService) {
        return new InstrumentService(storageService, mock(InstrumentValidator.class),
                new InstrumentProperties(), mock(InstrumentOutboxService.class));
    }

    private InstrumentResponse delivery(String symbol, InstrumentStatus status) {
        return response(symbol, InstrumentType.DELIVERY, ContractType.LINEAR_DELIVERY,
                null, null, null, status);
    }

    private InstrumentResponse option(String symbol, InstrumentStatus status) {
        return response(symbol, InstrumentType.OPTION, ContractType.VANILLA_OPTION,
                "1", 50_000_000_000L, OptionType.CALL, status);
    }

    private InstrumentResponse linearPerpetual(String symbol, long version, long priceTickUnits) {
        InstrumentResponse template = response(symbol, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL,
                null, null, null, InstrumentStatus.TRADING);
        return new InstrumentResponse(template.instrumentId(), template.baseAssetId(), template.quoteAssetId(), template.settleAssetId(), template.contractValueAssetId(), template.symbol(), version, template.instrumentType(), template.contractType(),
                template.baseAsset(), template.quoteAsset(), template.settleAsset(), template.contractMultiplierPpm(),
                template.contractValueAsset(), priceTickUnits, template.quantityStepUnits(), template.minQuantitySteps(),
                template.maxQuantitySteps(), template.minNotionalUnits(), template.maxNotionalUnits(),
                template.notionalMultiplierUnits(), template.pricePrecision(), template.quantityPrecision(),
                template.supportedOrderTypes(), template.supportedTimeInForce(), template.postOnlyEnabled(),
                template.reduceOnlyEnabled(), template.marketOrderEnabled(), template.maxLeveragePpm(),
                template.initialMarginRatePpm(), template.maintenanceMarginRatePpm(), template.makerFeeRatePpm(),
                template.takerFeeRatePpm(), template.maxPositionNotionalUnits(),
                template.userOpenInterestLimitRatePpm(), template.userOpenInterestLimitFloorUnits(),
                template.fundingIntervalHours(), template.interestRatePpm(), template.fundingRateCapPpm(),
                template.fundingRateFloorPpm(), template.impactNotionalUnits(), template.minValidIndexSources(),
                template.expiryTime(), template.deliveryTime(), template.underlyingInstrumentId(), template.underlyingProductLine(), template.strikePriceUnits(),
                template.optionType(), template.optionExerciseStyle(), template.settlementMethod(), template.status(),
                template.effectiveTime(), template.createdAt(), template.updatedAt(), template.riskLimitBrackets(),
                template.indexSources());
    }

    private InstrumentUpsertRequest request(InstrumentResponse source, long priceTickUnits) {
        return new InstrumentUpsertRequest(source.instrumentId(), source.symbol(), source.instrumentType(), source.contractType(), source.baseAssetId(), source.quoteAssetId(), source.settleAssetId(), source.contractMultiplierPpm(), source.contractValueAssetId(), priceTickUnits, source.quantityStepUnits(), source.minQuantitySteps(),
                source.maxQuantitySteps(), source.minNotionalUnits(), source.maxNotionalUnits(),
                source.notionalMultiplierUnits(), source.pricePrecision(), source.quantityPrecision(),
                source.supportedOrderTypes(), source.supportedTimeInForce(), source.postOnlyEnabled(),
                source.reduceOnlyEnabled(), source.marketOrderEnabled(), source.maxLeveragePpm(),
                source.initialMarginRatePpm(), source.maintenanceMarginRatePpm(), source.makerFeeRatePpm(),
                source.takerFeeRatePpm(), source.maxPositionNotionalUnits(), source.userOpenInterestLimitRatePpm(),
                source.userOpenInterestLimitFloorUnits(), source.fundingIntervalHours(), source.interestRatePpm(),
                source.fundingRateCapPpm(), source.fundingRateFloorPpm(), source.impactNotionalUnits(),
                source.minValidIndexSources(), source.expiryTime(), source.deliveryTime(), source.underlyingInstrumentId(), source.underlyingProductLine(),
                source.strikePriceUnits(), source.optionType(), source.optionExerciseStyle(), source.settlementMethod(),
                source.status(), source.effectiveTime(), source.riskLimitBrackets(), source.indexSources());
    }

    private InstrumentResponse response(String symbol,
                                        InstrumentType instrumentType,
                                        ContractType contractType,
                                        String underlyingInstrumentId,
                                        Long strikePriceUnits,
                                        OptionType optionType,
                                        InstrumentStatus status) {
        Instant now = Instant.parse("2026-03-27T08:05:00Z");
        return new InstrumentResponse(
                1, 3, 1, 1, 1, symbol,
                2L,
                instrumentType,
                contractType,
                "BTC",
                "USDT",
                "USDT",
                1_000_000L,
                "USDT",
                10_000_000L,
                100_000L,
                1L,
                100_000L,
                500_000_000L,
                1_000_000_000_000_000L,
                10_000L,
                1,
                3,
                List.of("LIMIT"),
                List.of("GTC", "IOC"),
                true,
                true,
                false,
                100_000_000L,
                10_000L,
                5_000L,
                200L,
                500L,
                500_000_000_000_000L,
                300_000L,
                25_000_000_000_000L,
                0,
                0L,
                0L,
                0L,
                1_000_000_000_000L,
                2,
                Instant.parse("2026-03-27T08:00:00Z"),
                Instant.parse("2026-03-27T08:05:00Z"),
                underlyingInstrumentId, underlyingInstrumentId == null ? null : com.surprising.product.api.ProductLine.LINEAR_PERPETUAL,
                strikePriceUnits,
                optionType,
                optionType == null ? null : OptionExerciseStyle.EUROPEAN,
                ContractSettlementMethod.CASH,
                status,
                now.minusSeconds(600),
                now.minusSeconds(600),
                now,
                List.of(),
                List.of());
    }

}
