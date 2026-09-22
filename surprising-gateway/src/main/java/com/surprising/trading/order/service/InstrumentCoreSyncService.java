package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.maintenance.MaintenanceAeronGateway;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private volatile Map<String, InstrumentResponse> startupInstruments;
    private record Attempt(boolean applied, long retryAfterNanos, String error) { }
    public record SyncState(String productLine, String symbol, String state, String error) { }

    public InstrumentCoreSyncService(@Qualifier("orderInstrumentSnapshotCache") InstrumentSnapshotCache cache,
            MaintenanceAeronGateway gateway, TradingOrderProperties properties) {
        this.cache=cache; this.gateway=gateway; this.line=properties.getKafka().getProductLine();
    }

    @Scheduled(fixedDelayString="${trading.order.instrument-sync-delay-ms:250}")
    public synchronized void reconcile() {
        if (!cache.initialized(line)) return;
        if (startupInstruments == null) {
            var frozen = new LinkedHashMap<String, InstrumentResponse>();
            cache.current(line).stream().sorted(Comparator.comparing(InstrumentResponse::symbol))
                    .forEach(value -> frozen.put(value.symbol(), value));
            startupInstruments = Collections.unmodifiableMap(frozen);
        }
        long now=System.nanoTime();
        for (var value : startupInstruments.values()) {
            var attempt=attempts.get(value.symbol());
            if (attempt!=null && (attempt.applied() || now < attempt.retryAfterNanos())) continue;
            registerStartupInstrument(value);
        }
    }

    private void registerStartupInstrument(InstrumentResponse instrument) {
        String error=null;
        try {
            long settleScale=instrument.contractType().isInverse()
                    ?cache.scale(line,instrument.settleAsset()).orElseThrow(()->new IllegalStateException("settlement asset scale is missing")):1L;
            var command=command(instrument,settleScale);
            var id=UUID.randomUUID();
            var response=gateway.command(CoreMessageType.REGISTER_INSTRUMENT,id,0,TradingCommandCodec.encodeRegisterInstrument(command));
            if (response.commandStatus()!=ResponseStatus.APPLIED) {
                error=response.resultCode().name();
            }
        } catch (RuntimeException failure) { error=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage(); }
        attempts.put(instrument.symbol(),new Attempt(error==null,
                System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5),error));
    }

    public SyncState state(String symbol, ProductLine requestedLine) {
        if (requestedLine!=line) throw new IllegalArgumentException("product line mismatch");
        var frozen = startupInstruments;
        var instrument = frozen == null ? cache.current(line,symbol).orElse(null) : frozen.get(symbol);
        if (instrument == null) throw new IllegalArgumentException("instrument not found in startup registry");
        var attempt=attempts.get(instrument.symbol());
        boolean applied=attempt!=null && attempt.applied();
        return new SyncState(line.name(),instrument.symbol(),
                applied?"APPLIED":attempt!=null && attempt.error()!=null?"BLOCKED":"PENDING",
                attempt==null?null:attempt.error());
    }

    static RegisterInstrumentCommand command(InstrumentResponse value,long settleScale) {
        var brackets=value.riskLimitBrackets().stream().map(b->new CoreRiskLimitBracket(b.bracketNo(),
                b.notionalFloorUnits(),b.notionalCapUnits(),b.maxLeveragePpm(),b.initialMarginRatePpm(),
                b.maintenanceMarginRatePpm(),b.optionMarginFactorPpm())).toList();
        long strike=value.strikePriceUnits()==null?0:value.strikePriceUnits()/value.priceTickUnits();
        if (value.strikePriceUnits()!=null && value.strikePriceUnits()%value.priceTickUnits()!=0)
            throw new IllegalArgumentException("strike price does not align with tick size");
        return new RegisterInstrumentCommand(value.symbol(),value.contractType().ordinal(),
                value.baseAsset(),value.quoteAsset(),value.settleAsset(),value.notionalMultiplierUnits(),value.priceTickUnits(),
                settleScale,value.initialMarginRatePpm(),value.maintenanceMarginRatePpm(),
                value.makerFeeRatePpm(),value.takerFeeRatePpm(),value.expiryTime()==null?0:value.expiryTime().toEpochMilli(),
                value.optionType()==null?-1:value.optionType().ordinal(),strike,value.maxLeveragePpm(),value.maxPositionNotionalUnits(),
                value.userOpenInterestLimitRatePpm(),value.userOpenInterestLimitFloorUnits(),brackets);
    }
}
