package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.maintenance.MaintenanceAeronGateway;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Low-rate control-plane reconciliation on the scheduler, never on user order ingress. */
@Service
public class InstrumentCoreSyncService {
    private final InstrumentSnapshotCache cache;
    private final MaintenanceAeronGateway gateway;
    private final ProductLine line;
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private record Attempt(long auditId, boolean applied, long retryAfterNanos, String error) { }
    public record SyncState(String productLine, String symbol, String requestedChangeId, String appliedChangeId,
                            String state, String error) { }

    public InstrumentCoreSyncService(@Qualifier("orderInstrumentSnapshotCache") InstrumentSnapshotCache cache,
            MaintenanceAeronGateway gateway, TradingOrderProperties properties) {
        this.cache=cache; this.gateway=gateway; this.line=properties.getKafka().getProductLine();
    }

    @Scheduled(fixedDelayString="${trading.order.instrument-sync-delay-ms:250}")
    public void reconcile() {
        if (!cache.initialized(line)) return;
        long now=System.nanoTime();
        InstrumentResponse selected=null;
        for (var value : cache.current(line)) {
            var attempt=attempts.get(value.symbol());
            if (attempt!=null && attempt.auditId()==value.lastChangeId()
                    && (attempt.applied() || now < attempt.retryAfterNanos())) continue;
            if (selected==null || selected.lastChangeId()<value.lastChangeId()) selected=value;
        }
        if (selected==null) return;
        String error=null;
        try {
            long settleScale=selected.contractType().isInverse()
                    ?cache.scale(line,selected.settleAsset()).orElseThrow(()->new IllegalStateException("settlement asset scale is missing")):1L;
            var command=command(selected,settleScale);
            var id=UUID.randomUUID();
            var response=gateway.command(CoreMessageType.UPSERT_INSTRUMENT,id,0,TradingCommandCodec.encodeUpsertInstrument(command));
            if (response.commandStatus()!=ResponseStatus.APPLIED) {
                error=response.resultCode().name();
            }
        } catch (RuntimeException failure) { error=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage(); }
        attempts.put(selected.symbol(),new Attempt(selected.lastChangeId(),error==null,
                System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5),error));
    }

    public SyncState state(String symbol, ProductLine requestedLine) {
        if (requestedLine!=line) throw new IllegalArgumentException("product line mismatch");
        var current=cache.current(line,symbol).orElseThrow(()->new IllegalArgumentException("instrument not found"));
        var attempt=attempts.get(current.symbol());
        boolean applied=attempt!=null && attempt.applied() && attempt.auditId()==current.lastChangeId();
        return new SyncState(line.name(),current.symbol(),Long.toString(current.lastChangeId()),
                attempt!=null && attempt.applied()?Long.toString(attempt.auditId()):"0",applied?"APPLIED":attempt!=null && attempt.error()!=null?"BLOCKED":"PENDING",
                attempt==null?null:attempt.error());
    }

    static UpsertInstrumentCommand command(InstrumentResponse value,long settleScale) {
        var brackets=value.riskLimitBrackets().stream().map(b->new CoreRiskLimitBracket(b.bracketNo(),
                b.notionalFloorUnits(),b.notionalCapUnits(),b.maxLeveragePpm(),b.initialMarginRatePpm(),
                b.maintenanceMarginRatePpm(),b.optionMarginFactorPpm())).toList();
        long strike=value.strikePriceUnits()==null?0:value.strikePriceUnits()/value.priceTickUnits();
        if (value.strikePriceUnits()!=null && value.strikePriceUnits()%value.priceTickUnits()!=0)
            throw new IllegalArgumentException("strike price does not align with tick size");
        return new UpsertInstrumentCommand(value.symbol(),value.changeId(),value.contractType().ordinal(),
                value.baseAsset(),value.quoteAsset(),value.settleAsset(),value.notionalMultiplierUnits(),value.priceTickUnits(),
                settleScale,value.initialMarginRatePpm(),value.maintenanceMarginRatePpm(),
                value.makerFeeRatePpm(),value.takerFeeRatePpm(),value.expiryTime()==null?0:value.expiryTime().toEpochMilli(),
                value.optionType()==null?-1:value.optionType().ordinal(),strike,value.maxLeveragePpm(),value.maxPositionNotionalUnits(),
                value.userOpenInterestLimitRatePpm(),value.userOpenInterestLimitFloorUnits(),brackets,
                value.status().ordinal(),value.lastChangeId());
    }
}
