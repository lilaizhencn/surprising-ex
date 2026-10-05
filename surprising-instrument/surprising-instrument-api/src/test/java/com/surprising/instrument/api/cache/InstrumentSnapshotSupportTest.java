package com.surprising.instrument.api.cache;

import com.surprising.instrument.api.model.ContractSettlementMethod;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.instrument.api.model.InstrumentEventType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.OptionExerciseStyle;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstrumentSnapshotSupportTest {

    @Test
    void ignoresEventsFromOtherProductLinesInSharedTopic() throws Exception {
        InstrumentEvent event = new InstrumentEvent(
                1, "BTC-USDT-260925",
                1L,
                InstrumentStatus.TRADING,
                InstrumentEventType.UPSERTED,
                Instant.parse("2026-08-04T00:00:00Z"),
                instrument("BTC-USDT-260925"),
                ProductLine.LINEAR_DELIVERY,
                1L);
        ObjectMapper mapper = new ObjectMapper();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "surprising.instrument.events.v1",
                11,
                0L,
                "LINEAR_DELIVERY:BTC-USDT-260925",
                mapper.writeValueAsString(event));
        InstrumentSnapshotCache cache = new InstrumentSnapshotCache();

        InstrumentEvent returned = InstrumentSnapshotSupport.consume(
                JsonMapper.builder().build(),
                record,
                cache,
                ProductLine.OPTION,
                "期权测试");

        assertEquals(event, returned);
        assertTrue(cache.current(ProductLine.OPTION).isEmpty());
    }

    @Test
    void initializesAnEmptyCatalogForLaterHotListing() {
        var rpc = (com.surprising.instrument.api.client.InstrumentRpcApi) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.surprising.instrument.api.client.InstrumentRpcApi.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("snapshot")) throw new UnsupportedOperationException();
                    return new com.surprising.instrument.api.model.InstrumentSnapshotResponse(ProductLine.OPTION,
                            0L, "", List.of(), java.util.Map.of());
                });
        var cache = new InstrumentSnapshotCache();
        InstrumentSnapshotSupport.initialize(rpc, cache, ProductLine.OPTION, "test");
        assertTrue(cache.initialized(ProductLine.OPTION));
    }

    @Test
    void appliesAssetScalesWithNewContractAndIgnoresValidRedelivery() {
        var cache = new InstrumentSnapshotCache();
        cache.replace(ProductLine.OPTION, List.of(), java.util.Map.of("USDT", 100_000_000L));
        var snapshot = instrument("BTC-USDT-260925");
        var event = new InstrumentEvent(1, snapshot.symbol(), 1, snapshot.status(), InstrumentEventType.UPSERTED,
                Instant.now(), snapshot, ProductLine.OPTION, 1, java.util.Map.of("BTC", 100_000_000L));
        InstrumentSnapshotSupport.apply(cache, "OPTION:1", event, ProductLine.OPTION, "test");
        assertEquals(100_000_000L, cache.scale(ProductLine.OPTION, "BTC").orElseThrow());
        assertEquals(100_000_000L, cache.scale(ProductLine.OPTION, "USDT").orElseThrow());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                InstrumentSnapshotSupport.apply(cache, "OPTION:1", event, ProductLine.OPTION, "test"));
        var invalid = new InstrumentEvent(1, "WRONG", 1, snapshot.status(), InstrumentEventType.UPSERTED,
                Instant.now(), snapshot, ProductLine.OPTION, 1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                InstrumentSnapshotSupport.apply(cache, "OPTION:1", invalid, ProductLine.OPTION, "test"));
    }

    private InstrumentResponse instrument(String symbol) {
        return new InstrumentResponse(
                1, 3, 1, 1, 1, symbol,
                1L,
                InstrumentType.OPTION,
                ContractType.VANILLA_OPTION,
                "BTC",
                "USDT",
                "USDT",
                1_000_000L,
                "USDT",
                10_000_000L,
                100_000L,
                1L,
                100_000L,
                1L,
                1_000_000_000_000L,
                10_000L,
                1,
                3,
                List.of("LIMIT"),
                List.of("GTC"),
                true,
                true,
                true,
                100_000_000L,
                10_000L,
                5_000L,
                200L,
                500L,
                1_000_000_000_000_000L,
                1_000_000L,
                1_000_000_000_000_000L,
                0,
                0L,
                0L,
                0L,
                1_000_000_000_000L,
                1,
                Instant.parse("2026-09-03T00:00:00Z"),
                Instant.parse("2026-09-03T00:00:00Z"),
                "BTC-USDT", com.surprising.product.api.ProductLine.SPOT,
                5_900_000_000_000L,
                OptionType.CALL,
                OptionExerciseStyle.EUROPEAN,
                ContractSettlementMethod.CASH,
                InstrumentStatus.TRADING,
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T00:00:00Z"),
                List.of(),
                List.of());
    }
}
