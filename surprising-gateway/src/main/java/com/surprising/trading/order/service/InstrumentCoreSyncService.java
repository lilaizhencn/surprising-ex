package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.maintenance.MaintenanceAeronGateway;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.util.Comparator;
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
    private record Attempt(RegisterInstrumentCommand configuration, UUID commandId, boolean outcomeUnknown, boolean applied,
                           long retryAfterNanos, String error) { }
    public record SyncState(String productLine, String instrumentId, String state, String error, long appliedChangeId) { }

    public InstrumentCoreSyncService(@Qualifier("orderInstrumentSnapshotCache") InstrumentSnapshotCache cache,
            MaintenanceAeronGateway gateway, TradingOrderProperties properties) {
        this.cache=cache; this.gateway=gateway; this.line=properties.getKafka().getProductLine();
    }

    @Scheduled(fixedDelayString="${trading.order.instrument-sync-delay-ms:250}")
    public synchronized void reconcile() {
        if (!cache.initialized(line)) return;
        long now=System.nanoTime();
        for (var value : cache.current(line).stream().sorted(Comparator.comparing(InstrumentResponse::instrumentId)).toList()) {
            var attempt=attempts.get(Integer.toString(value.instrumentId()));
            RegisterInstrumentCommand configuration;
            try {
                configuration=command(value);
            } catch (RuntimeException failure) {
                if (attempt!=null && attempt.configuration()==null && now<attempt.retryAfterNanos()) continue;
                attempts.put(Integer.toString(value.instrumentId()),new Attempt(null,null,false,false,
                        now+java.util.concurrent.TimeUnit.SECONDS.toNanos(5),error(failure)));
                continue;
            }
            if (attempt!=null && configuration.equals(attempt.configuration())
                    && (attempt.applied() || now < attempt.retryAfterNanos())) continue;
            applyConfiguration(configuration,attempt);
        }
    }

    private void applyConfiguration(RegisterInstrumentCommand configuration,Attempt previous) {
        String error=null;
        UUID id=previous!=null && configuration.equals(previous.configuration()) && previous.outcomeUnknown()
                ?previous.commandId():UUID.randomUUID();
        boolean outcomeUnknown=false;
        boolean applied=false;
        try {
            var outcome=gateway.commandOutcome(CoreMessageType.REGISTER_INSTRUMENT,id,0,
                    TradingCommandCodec.encodeRegisterInstrument(configuration));
            if (outcome instanceof CoreCommandOutcome.Terminal terminal) {
                applied=terminal.response().commandStatus()==ResponseStatus.APPLIED;
                if (!applied) error=terminal.response().resultCode().name();
            } else if (outcome instanceof CoreCommandOutcome.ResultUnknown) {
                outcomeUnknown=true;
                error="COMMAND_OUTCOME_UNKNOWN";
            } else if (outcome instanceof CoreCommandOutcome.NotAccepted notAccepted) {
                error=notAccepted.reason().name();
            }
        } catch (RuntimeException failure) {
            outcomeUnknown=true;
            error=error(failure);
        }
        attempts.put(configuration.instrumentId(),new Attempt(configuration,id,outcomeUnknown,applied,
                System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5),error));
    }

    public SyncState state(String instrumentId, ProductLine requestedLine) {
        if (requestedLine!=line) throw new IllegalArgumentException("product line mismatch");
        var instrument = cache.current(line, com.surprising.product.api.InstrumentIds.parse(instrumentId)).orElse(null);
        if (instrument == null) throw new IllegalArgumentException("instrument not found in configuration cache");
        var attempt=attempts.get(Integer.toString(instrument.instrumentId()));
        RegisterInstrumentCommand configuration;
        try {
            configuration=command(instrument);
        } catch (RuntimeException failure) {
            return new SyncState(line.name(),Integer.toString(instrument.instrumentId()),"BLOCKED",error(failure),0);
        }
        boolean currentAttempt=attempt!=null && configuration.equals(attempt.configuration());
        boolean applied=currentAttempt && attempt.applied();
        return new SyncState(line.name(),Integer.toString(instrument.instrumentId()),
                applied?"APPLIED":currentAttempt && attempt.error()!=null?"BLOCKED":"PENDING",
                currentAttempt?attempt.error():null, applied?instrument.lastChangeId():0);
    }

    RegisterInstrumentCommand command(InstrumentResponse value) {
        long settleScale=value.contractType().isInverse()
                ?cache.scale(line,value.settleAsset()).orElseThrow(
                        ()->new IllegalStateException("settlement asset scale is missing")):1L;
        return command(value,settleScale);
    }

    private static RegisterInstrumentCommand command(InstrumentResponse value,long settleScale) {
        var brackets=value.riskLimitBrackets().stream().map(b->new CoreRiskLimitBracket(b.bracketNo(),
                b.notionalFloorUnits(),b.notionalCapUnits(),b.maxLeveragePpm(),b.initialMarginRatePpm(),
                b.maintenanceMarginRatePpm(),b.optionMarginFactorPpm())).toList();
        long strike=value.strikePriceUnits()==null?0:value.strikePriceUnits()/value.priceTickUnits();
        if (value.strikePriceUnits()!=null && value.strikePriceUnits()%value.priceTickUnits()!=0)
            throw new IllegalArgumentException("strike price does not align with tick size");
        int supportedOrderTypes=0;
        if (value.supportedOrderTypes()!=null) {
            for (String orderType : value.supportedOrderTypes()) {
                if ("LIMIT".equals(orderType)) supportedOrderTypes|=1 << (CoreOrderType.LIMIT.wireCode()-1);
                if ("MARKET".equals(orderType)) supportedOrderTypes|=1 << (CoreOrderType.MARKET.wireCode()-1);
            }
        }
        int supportedTimeInForce=0;
        if (value.supportedTimeInForce()!=null) {
            for (String timeInForce : value.supportedTimeInForce()) {
                for (CoreTimeInForce supported : CoreTimeInForce.values()) {
                    if (supported.name().equals(timeInForce)) {
                        supportedTimeInForce|=1 << (supported.wireCode()-1);
                    }
                }
            }
        }
        return new RegisterInstrumentCommand(Integer.toString(value.instrumentId()),value.contractType().ordinal(),
                value.baseAsset(),value.quoteAsset(),value.settleAsset(),value.notionalMultiplierUnits(),value.priceTickUnits(),
                settleScale,value.initialMarginRatePpm(),value.maintenanceMarginRatePpm(),
                value.makerFeeRatePpm(),value.takerFeeRatePpm(),value.expiryTime()==null?0:value.expiryTime().toEpochMilli(),
                value.optionType()==null?-1:value.optionType().ordinal(),strike,value.maxLeveragePpm(),value.maxPositionNotionalUnits(),
                value.userOpenInterestLimitRatePpm(),value.userOpenInterestLimitFloorUnits(),brackets,
                value.status().ordinal(),value.marketOrderEnabled(),value.postOnlyEnabled(),value.reduceOnlyEnabled(),
                supportedOrderTypes,supportedTimeInForce);
    }

    private static String error(RuntimeException failure) {
        return failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
    }
}
