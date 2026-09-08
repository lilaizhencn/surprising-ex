package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.execution.CoreProbeState;
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
    void commitsConfigurationThenPauseAndConfirmsActualAppliedResponse(ProductLine line) {
        var cache=new InstrumentSnapshotCache(); var properties=new TradingOrderProperties(); properties.getKafka().setProductLine(line);
        var gateway=mock(MaintenanceAeronGateway.class); var sequence=new AtomicLong();
        try(var state=new CoreProbeState(line)) {
            when(gateway.command(any(),any(),anyLong(),any())).thenAnswer(call->{
                long seq=sequence.incrementAndGet();
                return state.apply(new CoreMessage(CoreMessageHeader.command(call.getArgument(0),call.getArgument(1),line,
                        CommandSource.OPERATIONS,993,seq,0,1_700_000_000_000L+seq,seq),call.getArgument(3)));
            });
            var service=new InstrumentCoreSyncService(cache,gateway,properties);
            cache.replace(line,List.of(row(line,1,InstrumentStatus.TRADING)),java.util.Map.of("BTC",1000L,"USDT",100_000_000L));
            assertThat(service.state("BTC-USDT",line).state()).isEqualTo("PENDING");
            service.reconcile();
            assertThat(service.state("BTC-USDT",line).state()).isEqualTo("APPLIED");
            service.reconcile(); assertThat(sequence.get()).isEqualTo(1);
            var pause=row(line,2,InstrumentStatus.HALT);
            cache.apply(new InstrumentEvent("BTC-USDT",2,InstrumentStatus.HALT,InstrumentEventType.STATUS_CHANGED,Instant.now(),pause,line,2));
            service.reconcile();
            assertThat(service.state("BTC-USDT",line).appliedChangeId()).isEqualTo("2");
            assertThat(state.tradingState().instruments().get("BTC-USDT").status()).isEqualTo(InstrumentStatus.HALT);
            assertThat(state.tradingState().instruments().get("BTC-USDT").changeId()).isEqualTo(1);
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
        when(v.riskLimitBrackets()).thenReturn(List.of(new RiskLimitBracket(1,0,1_000_000,10_000_000,100_000,50_000,1_000_000)));
        if (type.isDelivery() || type.isOption()) when(v.expiryTime()).thenReturn(Instant.ofEpochMilli(2_000_000_000_000L));
        if (type.isOption()) { when(v.optionType()).thenReturn(OptionType.CALL); when(v.strikePriceUnits()).thenReturn(100L); }
        when(v.status()).thenReturn(status); return v;
    }
}
