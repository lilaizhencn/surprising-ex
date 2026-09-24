package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.*;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.maintenance.MaintenanceAeronGateway;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.util.List;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InstrumentCoreSyncServiceTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void appliesStartupAndLaterConfigurationChangesThroughTheSameCoreCommand(ProductLine line) {
        var cache=new InstrumentSnapshotCache(); var properties=new TradingOrderProperties(); properties.getKafka().setProductLine(line);
        var gateway=mock(MaintenanceAeronGateway.class); var sequence=new AtomicLong();
        try(var state=new TradingCoreRuntime(line)) {
            when(gateway.commandOutcome(any(),any(),anyLong(),any())).thenAnswer(call->{
                long seq=sequence.incrementAndGet();
                var response=state.apply(new CoreMessage(CoreMessageHeader.command(call.getArgument(0),call.getArgument(1),line,
                        CommandSource.OPERATIONS,993,seq,0,1_700_000_000_000L+seq,seq),call.getArgument(3)));
                return new com.surprising.aeron.client.CoreCommandOutcome.Terminal(response);
            });
            var service=new InstrumentCoreSyncService(cache,gateway,properties);
            cache.replace(line,List.of(row(line,1,InstrumentStatus.TRADING)),java.util.Map.of("BTC",1000L,"USDT",100_000_000L));
            assertThat(service.state("BTC-USDT",line).state()).isEqualTo("PENDING");
            service.reconcile();
            assertThat(service.state("BTC-USDT",line).state()).isEqualTo("APPLIED");
            service.reconcile(); assertThat(sequence.get()).isEqualTo(1);
            var startupInstrument=state.tradingState().instruments().get("BTC-USDT");
            var sealSequence=sequence.incrementAndGet();
            state.apply(new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                    java.util.UUID.randomUUID(),line,CommandSource.OPERATIONS,993,sealSequence,0,
                    1_700_000_000_000L+sealSequence,sealSequence),CoreProtocol.probePayload(1)));
            var pause=row(line,2,InstrumentStatus.HALT);
            cache.apply(new InstrumentEvent("BTC-USDT",2,InstrumentStatus.TRADING,InstrumentEventType.UPSERTED,Instant.now(),pause,line,2));
            service.reconcile();
            assertThat(sequence.get()).isEqualTo(sealSequence+1);
            assertThat(service.state("BTC-USDT",line).state()).isEqualTo("APPLIED");
            var updatedInstrument=state.tradingState().instruments().get("BTC-USDT");
            assertThat(updatedInstrument).isSameAs(startupInstrument);
            assertThat(updatedInstrument.makerFeeRatePpm()).isEqualTo(2_000L);
            assertThat(updatedInstrument.instrumentStatus()).isEqualTo(InstrumentStatus.HALT);
            assertThat(updatedInstrument.marketOrderEnabled()).isTrue();

            var rejectedWhileHalted=state.apply(new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.PLACE_ORDER,java.util.UUID.randomUUID(),line,CommandSource.OPERATIONS,
                    11,sequence.incrementAndGet(),11,1_700_000_000_000L+sequence.get(),sequence.get()),
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(90,"BTC-USDT",CoreOrderSide.BUY,
                            1,1,false,CoreMarginMode.CROSS,CorePositionSide.NET,CoreOrderType.LIMIT,
                            CoreTimeInForce.GTC,false,"halted-order"))));
            assertThat(rejectedWhileHalted.commandStatus()).isEqualTo(ResponseStatus.REJECTED);

            var switches=row(line,3,InstrumentStatus.TRADING);
            when(switches.marketOrderEnabled()).thenReturn(false);
            cache.apply(new InstrumentEvent("BTC-USDT",3,InstrumentStatus.TRADING,InstrumentEventType.UPSERTED,
                    Instant.now(),switches,line,3));
            service.reconcile();
            assertThat(service.state("BTC-USDT",line).state()).isEqualTo("APPLIED");
            assertThat(updatedInstrument.instrumentStatus()).isEqualTo(InstrumentStatus.TRADING);
            assertThat(updatedInstrument.marketOrderEnabled()).isFalse();
            var rejectedMarket=state.apply(new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.PLACE_ORDER,java.util.UUID.randomUUID(),line,CommandSource.OPERATIONS,
                    11,sequence.incrementAndGet(),11,1_700_000_000_000L+sequence.get(),sequence.get()),
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(91,"BTC-USDT",CoreOrderSide.BUY,
                            0,1,false,CoreMarginMode.CROSS,CorePositionSide.NET,CoreOrderType.MARKET,
                            CoreTimeInForce.IOC,false,"market-disabled"))));
            assertThat(rejectedMarket.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            try (var restored=TradingCoreRuntime.fromSnapshot(line,state.snapshot(41))) {
                var recovered=restored.tradingState().instruments().get("BTC-USDT");
                assertThat(recovered.instrumentStatus()).isEqualTo(InstrumentStatus.TRADING);
                assertThat(recovered.marketOrderEnabled()).isFalse();
            }

            var restricted=row(line,4,InstrumentStatus.TRADING);
            when(restricted.marketOrderEnabled()).thenReturn(true);
            when(restricted.postOnlyEnabled()).thenReturn(false);
            when(restricted.reduceOnlyEnabled()).thenReturn(false);
            cache.apply(new InstrumentEvent("BTC-USDT",4,InstrumentStatus.TRADING,InstrumentEventType.UPSERTED,
                    Instant.now(),restricted,line,4));
            service.reconcile();
            assertThat(updatedInstrument.postOnlyEnabled()).isFalse();
            assertThat(updatedInstrument.reduceOnlyEnabled()).isFalse();
            var rejectedPostOnly=state.apply(new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.PLACE_ORDER,java.util.UUID.randomUUID(),line,CommandSource.OPERATIONS,
                    11,sequence.incrementAndGet(),11,1_700_000_000_000L+sequence.get(),sequence.get()),
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(92,"BTC-USDT",CoreOrderSide.BUY,
                            1,1,false,CoreMarginMode.CROSS,CorePositionSide.NET,CoreOrderType.LIMIT,
                            CoreTimeInForce.GTX,true,"post-only-disabled"))));
            assertThat(rejectedPostOnly.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            var rejectedReduceOnly=state.apply(new CoreMessage(CoreMessageHeader.command(
                    CoreMessageType.PLACE_ORDER,java.util.UUID.randomUUID(),line,CommandSource.OPERATIONS,
                    11,sequence.incrementAndGet(),11,1_700_000_000_000L+sequence.get(),sequence.get()),
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(93,"BTC-USDT",CoreOrderSide.SELL,
                            1,1,true,CoreMarginMode.CROSS,CorePositionSide.NET,CoreOrderType.LIMIT,
                            CoreTimeInForce.GTC,false,"reduce-only-disabled"))));
            assertThat(rejectedReduceOnly.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
        }
    }
    private InstrumentResponse row(ProductLine line,long audit,InstrumentStatus status) {
        var v=mock(InstrumentResponse.class); var type=ContractType.valueOf(line.contractTypeCode());
        when(v.symbol()).thenReturn("BTC-USDT"); when(v.changeId()).thenReturn(1L); when(v.lastChangeId()).thenReturn(audit);
        when(v.contractType()).thenReturn(type); when(v.baseAsset()).thenReturn("BTC"); when(v.quoteAsset()).thenReturn("USDT");
        when(v.settleAsset()).thenReturn(type.isInverse()?"BTC":"USDT"); when(v.notionalMultiplierUnits()).thenReturn(1L);
        when(v.priceTickUnits()).thenReturn(1L); when(v.initialMarginRatePpm()).thenReturn(100_000L);
        when(v.maintenanceMarginRatePpm()).thenReturn(50_000L); when(v.maxLeveragePpm()).thenReturn(10_000_000L);
        when(v.maxPositionNotionalUnits()).thenReturn(1_000_000L); when(v.userOpenInterestLimitFloorUnits()).thenReturn(1_000_000L);
        when(v.makerFeeRatePpm()).thenReturn(audit*1_000L);
        when(v.marketOrderEnabled()).thenReturn(true); when(v.postOnlyEnabled()).thenReturn(true);
        when(v.reduceOnlyEnabled()).thenReturn(true);
        when(v.supportedOrderTypes()).thenReturn(List.of("LIMIT","MARKET"));
        when(v.supportedTimeInForce()).thenReturn(List.of("GTC","IOC","FOK","GTX"));
        when(v.riskLimitBrackets()).thenReturn(List.of(new RiskLimitBracket(1,0,1_000_000,10_000_000,100_000,50_000,1_000_000)));
        if (type.isDelivery() || type.isOption()) when(v.expiryTime()).thenReturn(Instant.ofEpochMilli(2_000_000_000_000L));
        if (type.isOption()) { when(v.optionType()).thenReturn(OptionType.CALL); when(v.strikePriceUnits()).thenReturn(100L); }
        when(v.status()).thenReturn(status); return v;
    }
}
