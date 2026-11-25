package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.*;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.maintenance.service.MaintenanceAeronGateway;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InstrumentCoreSyncServiceTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void draftCanBeRegisteredAndRecoveredBeforeListing(ProductLine line) {
        var cache = new InstrumentSnapshotCache();
        var properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(line);
        var gateway = mock(MaintenanceAeronGateway.class);
        Map<String, RegisterInstrumentCommand> registeredCommands = new ConcurrentHashMap<>();
        when(gateway.commandOutcome(any(), any(), anyLong(), any())).thenAnswer(call -> {
            CoreMessageType type = call.getArgument(0);
            byte[] payload = call.getArgument(3);
            if (type == CoreMessageType.REGISTER_INSTRUMENT) {
                var cmd = TradingCommandCodec.decodeRegisterInstrument(payload, line);
                registeredCommands.put(cmd.instrumentId(), cmd);
            }
            return new com.surprising.aeron.client.CoreCommandOutcome.Terminal(
                    new CoreResponse(ResponseStatus.OK, ResponseStatus.APPLIED, CoreResultCode.NONE, 1L, 1L, new byte[0]));
        });
        var draft = row(line, 1, InstrumentStatus.DRAFT);
        when(draft.priceTickUnits()).thenReturn(10L);
        cache.replace(line, List.of(draft),
                Map.of("BTC", 100_000_000L, "USDT", 100_000_000L));
        var service = new InstrumentCoreSyncService(cache, gateway, properties);
        service.reconcile();
        assertThat(service.state("1", line).state()).isEqualTo("APPLIED");
        var registered = registeredCommands.get("1");
        assertThat(registered).isNotNull();
        assertThat(registered.instrumentStatusCode()).isEqualTo(InstrumentStatus.DRAFT.ordinal());
        assertThat(registered.priceTickUnits()).isEqualTo(draft.contractType().isInverse() ? 10 : 1);
        if (line.isOptionProduct()) assertThat(registered.strikePriceTicks()).isEqualTo(10);
        if (line.isDerivative()) assertThat(registered.orderProtection()).isEqualTo(properties.getRisk().protection());
        else assertThat(registered.orderProtection()).isNull();
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void appliesStartupAndLaterConfigurationChangesThroughTheSameCoreCommand(ProductLine line) {
        var cache = new InstrumentSnapshotCache();
        var properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(line);
        var gateway = mock(MaintenanceAeronGateway.class);
        var sequence = new AtomicLong();
        Map<String, RegisterInstrumentCommand> registeredCommands = new ConcurrentHashMap<>();
        when(gateway.commandOutcome(any(), any(), anyLong(), any())).thenAnswer(call -> {
            long seq = sequence.incrementAndGet();
            CoreMessageType type = call.getArgument(0);
            byte[] payload = call.getArgument(3);
            if (type == CoreMessageType.REGISTER_INSTRUMENT) {
                var cmd = TradingCommandCodec.decodeRegisterInstrument(payload, line);
                registeredCommands.put(cmd.instrumentId(), cmd);
            }
            return new com.surprising.aeron.client.CoreCommandOutcome.Terminal(
                    new CoreResponse(ResponseStatus.OK, ResponseStatus.APPLIED, CoreResultCode.NONE, seq, seq, new byte[0]));
        });
        var service = new InstrumentCoreSyncService(cache, gateway, properties);
        cache.replace(line, List.of(row(line, 1, InstrumentStatus.TRADING)), Map.of("BTC", 1000L, "USDT", 100_000_000L));
        assertThat(service.state("1", line).state()).isEqualTo("PENDING");
        service.reconcile();
        assertThat(service.state("1", line).state()).isEqualTo("APPLIED");
        service.reconcile();
        assertThat(sequence.get()).isEqualTo(1);
        var startupInstrument = registeredCommands.get("1");
        assertThat(startupInstrument.instrumentStatusCode()).isEqualTo(InstrumentStatus.TRADING.ordinal());

        var pause = row(line, 2, InstrumentStatus.HALT);
        cache.apply(new InstrumentEvent(1, "BTC-USDT", 2, InstrumentStatus.TRADING, InstrumentEventType.UPSERTED, Instant.now(), pause, line, 2));
        service.reconcile();
        assertThat(sequence.get()).isEqualTo(2);
        assertThat(service.state("1", line).state()).isEqualTo("APPLIED");
        var updatedInstrument = registeredCommands.get("1");
        assertThat(updatedInstrument.makerFeeRatePpm()).isEqualTo(2_000L);
        assertThat(updatedInstrument.instrumentStatusCode()).isEqualTo(InstrumentStatus.HALT.ordinal());
        assertThat(updatedInstrument.marketOrderEnabled()).isTrue();

        var switches = row(line, 3, InstrumentStatus.TRADING);
        when(switches.marketOrderEnabled()).thenReturn(false);
        cache.apply(new InstrumentEvent(1, "BTC-USDT", 3, InstrumentStatus.TRADING, InstrumentEventType.UPSERTED,
                Instant.now(), switches, line, 3));
        service.reconcile();
        assertThat(service.state("1", line).state()).isEqualTo("APPLIED");
        assertThat(registeredCommands.get("1").marketOrderEnabled()).isFalse();
    }

    private static InstrumentResponse row(ProductLine line, long changeId, InstrumentStatus status) {
        var r = mock(InstrumentResponse.class);
        when(r.instrumentId()).thenReturn(1);
        when(r.symbol()).thenReturn("BTC-USDT");
        when(r.baseAsset()).thenReturn("BTC");
        when(r.quoteAsset()).thenReturn("USDT");
        when(r.settleAsset()).thenReturn("USDT");
        when(r.contractType()).thenReturn(switch (line) {
            case SPOT -> ContractType.SPOT;
            case LINEAR_PERPETUAL -> ContractType.LINEAR_PERPETUAL;
            case INVERSE_PERPETUAL -> ContractType.INVERSE_PERPETUAL;
            case LINEAR_DELIVERY -> ContractType.LINEAR_DELIVERY;
            case INVERSE_DELIVERY -> ContractType.INVERSE_DELIVERY;
            case OPTION -> ContractType.VANILLA_OPTION;
        });
        when(r.supportedOrderTypes()).thenReturn(List.of("LIMIT", "MARKET"));
        when(r.supportedTimeInForce()).thenReturn(List.of("GTC", "IOC", "FOK", "GTX"));
        when(r.optionType()).thenReturn(OptionType.CALL);
        when(r.maxPositionNotionalUnits()).thenReturn(1_000_000L);
        when(r.userOpenInterestLimitFloorUnits()).thenReturn(1_000_000L);
        when(r.riskLimitBrackets()).thenReturn(List.of(
                new RiskLimitBracket(1, 0L, 1_000_000L, 10_000_000L, 100_000L, 50_000L, 1_000_000L)));
        when(r.priceTickUnits()).thenReturn(1L);
        when(r.quantityStepUnits()).thenReturn(1L);
        when(r.notionalMultiplierUnits()).thenReturn(1L);
        when(r.makerFeeRatePpm()).thenReturn(2_000L);
        when(r.takerFeeRatePpm()).thenReturn(5_000L);
        when(r.maxLeveragePpm()).thenReturn(10_000_000L);
        when(r.maintenanceMarginRatePpm()).thenReturn(50_000L);
        when(r.initialMarginRatePpm()).thenReturn(100_000L);
        when(r.strikePriceUnits()).thenReturn(100L);
        when(r.expiryTime()).thenReturn(Instant.ofEpochMilli(2_000_000_000_000L));
        when(r.status()).thenReturn(status);
        when(r.changeId()).thenReturn(changeId);
        when(r.lastChangeId()).thenReturn(changeId);
        when(r.marketOrderEnabled()).thenReturn(true);
        return r;
    }
}
