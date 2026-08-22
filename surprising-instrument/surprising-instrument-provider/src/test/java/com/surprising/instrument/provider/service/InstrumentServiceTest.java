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
                eq("BTC-USDT-260327"), eq("DELIVERY_SETTLEMENT"), event.capture(), any(Instant.class));
        assertThat(event.getValue()).isInstanceOf(DeliverySettlementEvent.class);
        DeliverySettlementEvent deliveryEvent = (DeliverySettlementEvent) event.getValue();
        assertThat(deliveryEvent.symbol()).isEqualTo("BTC-USDT-260327");
        assertThat(deliveryEvent.status()).isEqualTo(InstrumentStatus.CLOSED);
    }

    @Test
    void publishesOptionExerciseToProductTopic() {
        InstrumentOutboxService outboxService = mock(InstrumentOutboxService.class);
        InstrumentStorageService storageService = mock(InstrumentStorageService.class);
        when(storageService.latest("BTC-USDT"))
                .thenReturn(Optional.of(delivery("BTC-USDT", InstrumentStatus.TRADING)));
        InstrumentService service = new InstrumentService(storageService, mock(InstrumentValidator.class),
                new InstrumentProperties(), outboxService);
        InstrumentResponse instrument = option("BTC-USDT-260327-50000-C", InstrumentStatus.CLOSED);

        service.publishProductLifecycleEvent(instrument, 0L, 71_000_000L);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).enqueue(eq("INSTRUMENT"), eq(2L),
                eq("surprising.option.option.exercises.v1"),
                eq("BTC-USDT-260327-50000-C"), eq("OPTION_EXERCISE"), event.capture(), any(Instant.class));
        assertThat(event.getValue()).isInstanceOf(OptionExerciseEvent.class);
        OptionExerciseEvent optionEvent = (OptionExerciseEvent) event.getValue();
        assertThat(optionEvent.underlyingSymbol()).isEqualTo("BTC-USDT");
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
                eq("BTC-USDT-260327"), eq("DELIVERY_SETTLEMENT"), any(Object.class), any(Instant.class));
    }

    @Test
    void latestAcceptsMatchingProductLine() {
        InstrumentStorageService storageService = mock(InstrumentStorageService.class);
        InstrumentService service = service(storageService);
        InstrumentResponse instrument = option("BTC-USDT-260327-50000-C", InstrumentStatus.TRADING);
        when(storageService.latest("BTC-USDT-260327-50000-C", ProductLine.OPTION))
                .thenReturn(Optional.of(instrument));

        InstrumentResponse response = service.latest("BTC-USDT-260327-50000-C", ProductLine.OPTION);

        assertThat(response).isSameAs(instrument);
        verify(storageService).latest("BTC-USDT-260327-50000-C", ProductLine.OPTION);
    }

    @Test
    void latestRejectsMismatchedProductLine() {
        InstrumentStorageService storageService = mock(InstrumentStorageService.class);
        InstrumentService service = service(storageService);
        when(storageService.latest("BTC-USDT-260327", ProductLine.LINEAR_PERPETUAL))
                .thenReturn(Optional.empty());
        when(storageService.latest("BTC-USDT-260327"))
                .thenReturn(Optional.of(delivery("BTC-USDT-260327", InstrumentStatus.TRADING)));

        assertThatThrownBy(() -> service.latest("BTC-USDT-260327", ProductLine.LINEAR_PERPETUAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("instrument not found for productLine");
    }

    @Test
    void latestRejectsCorruptProductCurrentVersion() {
        InstrumentStorageService storageService = mock(InstrumentStorageService.class);
        InstrumentService service = service(storageService);
        when(storageService.latest("BTC-USDT-260327", ProductLine.OPTION))
                .thenReturn(Optional.of(delivery("BTC-USDT-260327", InstrumentStatus.TRADING)));

        assertThatThrownBy(() -> service.latest("BTC-USDT-260327", ProductLine.OPTION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("instrument product current mismatch");
    }

    @Test
    void settlementConfirmationRejectsInstrumentThatWasNotDrainedToSettling() {
        InstrumentStorageService storageService = mock(InstrumentStorageService.class);
        InstrumentService service = service(storageService);
        when(storageService.latest("BTC-USDT-260327", ProductLine.LINEAR_DELIVERY))
                .thenReturn(Optional.of(delivery("BTC-USDT-260327", InstrumentStatus.TRADING)));

        assertThatThrownBy(() -> service.closeForSettlement("BTC-USDT-260327", ProductLine.LINEAR_DELIVERY,
                100_000L, 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须先进入 SETTLING");
        verify(storageService, org.mockito.Mockito.never()).insert(any(), anyLong(), any(), any());
    }

    @Test
    void upsertCreatesImmutableLinearPerpetualV4AndAdvancesOnlyItsCurrentPointer() throws Exception {
        String symbol = "BTC-USDT-SWAP";
        InstrumentResponse v3 = linearPerpetual(symbol, 3L, 100_000_000_000L);
        InstrumentUpsertRequest request = request(v3, 10_000_000L);
        byte[] independentlySerializedV3 = OBJECT_MAPPER.writeValueAsBytes(v3);
        StatefulSerializedInstrumentStorage storageService = new StatefulSerializedInstrumentStorage();
        storageService.seedRow(symbol, 3L, independentlySerializedV3);
        storageService.seedGlobalPointer(symbol, 3L);
        storageService.seedGlobalPointer("ETH-USDT-SWAP", 7L);
        storageService.seedProductPointer(ProductLine.LINEAR_PERPETUAL, symbol, 3L);
        storageService.seedProductPointer(ProductLine.SPOT, "ETH-USDT", 11L);
        storageService.seedProductPointer(ProductLine.INVERSE_PERPETUAL, "BTC-USD-SWAP", 13L);
        Map<String, Long> globalPointersBefore = storageService.globalPointers();
        Map<String, Long> productPointersBefore = storageService.productPointers();
        InstrumentOutboxService outboxService = mock(InstrumentOutboxService.class);
        InstrumentService service = new InstrumentService(storageService, mock(InstrumentValidator.class),
                new InstrumentProperties(), outboxService);

        byte[] persistedV3Before = storageService.persistedBytes(symbol, 3L);
        InstrumentResponse created = service.upsert(request);
        byte[] persistedV3After = storageService.persistedBytes(symbol, 3L);
        InstrumentResponse independentlyReadV3 = storageService.persistedResponse(symbol, 3L);
        InstrumentResponse current = service.latest(symbol, ProductLine.LINEAR_PERPETUAL);

        assertThat(created.version()).isEqualTo(4L);
        assertThat(created.priceTickUnits()).isEqualTo(10_000_000L);
        assertThat(current).isEqualTo(created);
        assertThat(storageService.insertedVersion()).isEqualTo(4L);
        assertThat(storageService.insertedRequest()).isEqualTo(request);
        assertThat(persistedV3Before).containsExactly(independentlySerializedV3);
        assertThat(persistedV3After).containsExactly(independentlySerializedV3);
        assertThat(independentlyReadV3).isEqualTo(v3);

        Map<String, Long> expectedGlobalPointers = new HashMap<>(globalPointersBefore);
        expectedGlobalPointers.put(symbol, 4L);
        assertThat(storageService.globalPointers()).containsExactlyInAnyOrderEntriesOf(expectedGlobalPointers);
        Map<String, Long> expectedProductPointers = new HashMap<>(productPointersBefore);
        expectedProductPointers.put(ProductLine.LINEAR_PERPETUAL.name() + ":" + symbol, 4L);
        assertThat(storageService.productPointers()).containsExactlyInAnyOrderEntriesOf(expectedProductPointers);
        verify(outboxService).enqueue(eq("INSTRUMENT"), eq(4L),
                eq("surprising.instrument.events.v1"), eq("LINEAR_PERPETUAL:" + symbol), eq("UPSERTED"),
                any(Object.class), any(Instant.class));
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
                "BTC-USDT", 50_000_000_000L, OptionType.CALL, status);
    }

    private InstrumentResponse linearPerpetual(String symbol, long version, long priceTickUnits) {
        InstrumentResponse template = response(symbol, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL,
                null, null, null, InstrumentStatus.TRADING);
        return new InstrumentResponse(template.symbol(), version, template.instrumentType(), template.contractType(),
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
                template.expiryTime(), template.deliveryTime(), template.underlyingSymbol(), template.strikePriceUnits(),
                template.optionType(), template.optionExerciseStyle(), template.settlementMethod(), template.status(),
                template.effectiveTime(), template.createdAt(), template.updatedAt(), template.riskLimitBrackets(),
                template.indexSources());
    }

    private InstrumentUpsertRequest request(InstrumentResponse source, long priceTickUnits) {
        return new InstrumentUpsertRequest(source.symbol(), source.instrumentType(), source.contractType(),
                source.baseAsset(), source.quoteAsset(), source.settleAsset(), source.contractMultiplierPpm(),
                source.contractValueAsset(), priceTickUnits, source.quantityStepUnits(), source.minQuantitySteps(),
                source.maxQuantitySteps(), source.minNotionalUnits(), source.maxNotionalUnits(),
                source.notionalMultiplierUnits(), source.pricePrecision(), source.quantityPrecision(),
                source.supportedOrderTypes(), source.supportedTimeInForce(), source.postOnlyEnabled(),
                source.reduceOnlyEnabled(), source.marketOrderEnabled(), source.maxLeveragePpm(),
                source.initialMarginRatePpm(), source.maintenanceMarginRatePpm(), source.makerFeeRatePpm(),
                source.takerFeeRatePpm(), source.maxPositionNotionalUnits(), source.userOpenInterestLimitRatePpm(),
                source.userOpenInterestLimitFloorUnits(), source.fundingIntervalHours(), source.interestRatePpm(),
                source.fundingRateCapPpm(), source.fundingRateFloorPpm(), source.impactNotionalUnits(),
                source.minValidIndexSources(), source.expiryTime(), source.deliveryTime(), source.underlyingSymbol(),
                source.strikePriceUnits(), source.optionType(), source.optionExerciseStyle(), source.settlementMethod(),
                source.status(), source.effectiveTime(), source.riskLimitBrackets(), source.indexSources());
    }

    private InstrumentResponse response(String symbol,
                                        InstrumentType instrumentType,
                                        ContractType contractType,
                                        String underlyingSymbol,
                                        Long strikePriceUnits,
                                        OptionType optionType,
                                        InstrumentStatus status) {
        Instant now = Instant.parse("2026-03-27T08:05:00Z");
        return new InstrumentResponse(
                symbol,
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
                underlyingSymbol,
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

    private final class StatefulSerializedInstrumentStorage extends InstrumentStorageService {
        private final Map<String, Map<Long, byte[]>> rows = new HashMap<>();
        private final Map<String, Long> globalPointers = new HashMap<>();
        private final Map<String, Long> productPointers = new HashMap<>();
        private InstrumentUpsertRequest insertedRequest;
        private long insertedVersion;

        private StatefulSerializedInstrumentStorage() {
            super(null, null, null, null, null, null);
        }

        private void seedRow(String symbol, long version, byte[] serialized) {
            rows.computeIfAbsent(symbol, ignored -> new HashMap<>()).put(version, serialized.clone());
        }

        private void seedGlobalPointer(String symbol, long version) {
            globalPointers.put(symbol, version);
        }

        private void seedProductPointer(ProductLine productLine, String symbol, long version) {
            productPointers.put(productLine.name() + ":" + symbol, version);
        }

        private byte[] persistedBytes(String symbol, long version) {
            return rows.getOrDefault(symbol, Map.of()).get(version).clone();
        }

        private InstrumentResponse persistedResponse(String symbol, long version) {
            try {
                return OBJECT_MAPPER.readValue(persistedBytes(symbol, version), InstrumentResponse.class);
            } catch (Exception ex) {
                throw new AssertionError("failed to deserialize persisted instrument", ex);
            }
        }

        private Map<String, Long> globalPointers() {
            return Map.copyOf(globalPointers);
        }

        private Map<String, Long> productPointers() {
            return Map.copyOf(productPointers);
        }

        private InstrumentUpsertRequest insertedRequest() {
            return insertedRequest;
        }

        private long insertedVersion() {
            return insertedVersion;
        }

        @Override
        public long nextVersion(String symbol) {
            return rows.getOrDefault(symbol, Map.of()).keySet().stream().mapToLong(Long::longValue).max().orElse(0L) + 1L;
        }

        @Override
        public void insert(String symbol, long version, InstrumentUpsertRequest request, Instant now) {
            insertedRequest = request;
            insertedVersion = version;
            seedRow(symbol, version, OBJECT_MAPPER.writeValueAsBytes(
                    linearPerpetual(symbol, version, request.priceTickUnits())));
        }

        @Override
        public void setCurrentVersion(String symbol, long version, Instant now) {
            globalPointers.put(symbol, version);
        }

        @Override
        public void setCurrentVersion(ProductLine productLine, String symbol, long version, Instant now) {
            productPointers.put(productLine.name() + ":" + symbol, version);
        }

        @Override
        public Optional<InstrumentResponse> latest(String symbol) {
            Long version = globalPointers.get(symbol);
            return version == null ? Optional.empty() : version(symbol, version);
        }

        @Override
        public Optional<InstrumentResponse> latest(String symbol, ProductLine productLine) {
            Long version = productPointers.get(productLine.name() + ":" + symbol);
            return version == null ? Optional.empty() : version(symbol, version);
        }

        @Override
        public Optional<InstrumentResponse> version(String symbol, long version) {
            if (!rows.getOrDefault(symbol, Map.of()).containsKey(version)) {
                return Optional.empty();
            }
            return Optional.of(persistedResponse(symbol, version));
        }
    }
}
