package com.surprising.funding.provider.service;

import lombok.extern.slf4j.Slf4j;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.client.AeronLifecycleCoordinator;
import com.surprising.aeron.protocol.CoreFundingProgressCodec;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.funding.api.model.FundingPaymentQueryResponse;
import com.surprising.funding.api.model.FundingRateQueryResponse;
import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.api.model.FundingSettlementResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.repository.FundingLeaseRepository;
import com.surprising.funding.provider.repository.FundingPaymentRepository;
import com.surprising.funding.provider.repository.FundingRateInputRepository;
import com.surprising.funding.provider.repository.FundingRateRepository;
import com.surprising.funding.provider.repository.FundingSequenceRepository;
import com.surprising.funding.provider.repository.FundingSettlementRepository;
import com.surprising.price.api.model.PerpFundingRateEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class FundingService {


    private final FundingProperties properties;
    private final FundingLeaseRepository leaseRepository;
    private final FundingSequenceRepository sequenceRepository;
    private final FundingRateInputRepository rateInputRepository;
    private final FundingRateRepository rateRepository;
    private final FundingSettlementRepository settlementRepository;
    private final FundingPaymentRepository paymentRepository;
    private final LatestFundingRateCache latestFundingRateCache;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final FundingAeronGateway aeron;
    private final String nodeId;
    private final AeronLifecycleCoordinator lifecycleCoordinator = new AeronLifecycleCoordinator();
    public FundingService(FundingProperties properties,
                          FundingLeaseRepository leaseRepository,
                          FundingSequenceRepository sequenceRepository,
                          FundingRateInputRepository rateInputRepository,
                          FundingRateRepository rateRepository,
                          FundingSettlementRepository settlementRepository,
                          FundingPaymentRepository paymentRepository,
                          LatestFundingRateCache latestFundingRateCache,
                          @Qualifier("fundingKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
                          FundingAeronGateway aeron) {
        this.properties = properties;
        this.leaseRepository = leaseRepository;
        this.sequenceRepository = sequenceRepository;
        this.rateInputRepository = rateInputRepository;
        this.rateRepository = rateRepository;
        this.settlementRepository = settlementRepository;
        this.paymentRepository = paymentRepository;
        this.latestFundingRateCache = latestFundingRateCache;
        this.kafkaTemplate = kafkaTemplate;
        this.aeron = aeron;
        if (!properties.getKafka().isFundingProductLine()) {
            throw new IllegalArgumentException("funding provider requires a funding ProductLine");
        }
        this.nodeId = resolveNodeId(properties.getCoordination().getNodeId());
    }

    public void publishRates() {
        if (!properties.getCalculation().isEnabled()) return;
        Instant now = Instant.now();
        for (var input : rateInputRepository.find(properties.getCalculation().getMaxMarkAge())) {
            if (!ownsSymbol(input.instrumentId())) continue;
            long sequence = sequenceRepository.next(input.instrumentId());
            long rawRate = Math.addExact(input.interestRatePpm(), input.premiumRatePpm());
            long fundingRate = FundingMath.clampRate(rawRate, input.fundingRateFloorPpm(), input.fundingRateCapPpm());
            Instant fundingTime = FundingTime.nextFundingTime(now, input.fundingIntervalHours());
            FundingRateResponse rate = new FundingRateResponse(input.instrumentId(), sequence, fundingRate,
                    input.premiumRatePpm(), input.interestRatePpm(), fundingTime, input.fundingIntervalHours(),
                    "PREDICTED", now);
            latestFundingRateCache.update(rate);
            kafkaTemplate.send(properties.getKafka().getFundingRateTopic(), rate.instrumentId(), fundingRateEvent(rate));
        }
    }

    public synchronized SettlementCycle settleDueRates() {
        return lifecycleCoordinator.execute(this::settleDueRatesInternal);
    }

    private SettlementCycle settleDueRatesInternal() {
        if (!properties.getSettlement().isEnabled()) return SettlementCycle.disabled();
        Instant now = Instant.now();
        int dueRates = 0;
        int settledRates = 0;
        int pages = 0;
        int failedRates = 0;
        for (FundingRateResponse prediction : latestFundingRateCache.duePredictions(now).stream()
                .limit(properties.getSettlement().getBatchSize()).toList()) {
            FundingRateResponse rate = prediction;
            dueRates++;
            if (!ownsSymbol(rate.instrumentId())) continue;
            try {
                var gateResponse = aeron.query(CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY, UUID.randomUUID(),
                        com.surprising.aeron.protocol.CoreMaintenanceCodec.encodeQuery(
                                new com.surprising.aeron.protocol.CoreMaintenanceCodec.Query(rate.instrumentId(), 0, 1)));
                if (gateResponse == null || gateResponse.status() != com.surprising.aeron.protocol.ResponseStatus.OK) {
                    throw new IllegalStateException("Aeron maintenance state unavailable");
                }
                var gate = com.surprising.aeron.protocol.CoreMaintenanceCodec.decodePage(gateResponse.data()).state();
                if (gate.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.CLOSED) {
                    latestFundingRateCache.removeIfCurrent(rate);
                    continue; // No funding payment occurred; do not publish a false FINAL rate.
                }
                if (gate.mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT) continue;
                var probe = new FundingSettlementRepository.CoreSettlement(rate.fundingTime().toEpochMilli(), rate);
                CoreFundingProgressView persisted = decodeProgressOrQuery(rate.instrumentId(), probe, null);
                if (persisted != null && (persisted.settlementId() > probe.settlementId()
                        || persisted.complete() && persisted.settlementId() == probe.settlementId())) {
                    latestFundingRateCache.removeThrough(rate.instrumentId(), rate.fundingTime());
                    continue; // Only committed replay may publish FINAL history, never a replayed prediction.
                }
                FundingSettlementRepository.CoreSettlement settlement = settlementRepository.reserveCore(rate);
                rate = settlement.rate();
                String commandPrefix = properties.getKafka().getProductLine() + ":funding:"
                        + rate.instrumentId() + ':' + settlement.settlementId();
                long cursor = 0;
                if (persisted != null && !persisted.complete()) {
                    cursor = persisted.nextCursorUserId();
                }
                boolean complete = false;
                for (int page = 0; page < properties.getSettlement().getMaxPagesPerRun(); page++) {
                    UUID commandId = UUID.nameUUIDFromBytes((commandPrefix + ':' + cursor)
                            .getBytes(StandardCharsets.UTF_8));
                    CoreResponse response = aeron.commandWithResponse(CoreMessageType.APPLY_FUNDING, commandId,
                            TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(
                                    settlement.settlementId(), rate.instrumentId(), rate.fundingRatePpm(),
                                    cursor, ApplyFundingCommand.DEFAULT_MAX_USERS)));
                    pages++;
                    CoreFundingProgressView progress = decodeProgressOrQuery(rate.instrumentId(), settlement, response);
                    if (progress == null) {
                        throw new IllegalStateException("Aeron funding progress is required");
                    }
                    if (progress.complete()) {
                        complete = true;
                        break;
                    }
                    if (progress.nextCursorUserId() <= cursor) {
                        throw new IllegalStateException("Aeron funding cursor did not advance");
                    }
                    cursor = progress.nextCursorUserId();
                }
                if (!complete) continue;
                latestFundingRateCache.removeThrough(rate.instrumentId(), rate.fundingTime());
                settledRates++;
            } catch (Exception exception) {
                failedRates++;
                log.error("Aeron funding settlement failed instrumentId={} fundingTime={}: {}",
                        rate.instrumentId(), rate.fundingTime(), exception.getMessage(), exception);
            }
        }
        return new SettlementCycle(true, dueRates, settledRates, pages, failedRates);
    }

    private CoreFundingProgressView decodeProgressOrQuery(
            String instrumentId,
            FundingSettlementRepository.CoreSettlement settlement,
            CoreResponse response) {
        CoreResponse effective = response;
        if (effective == null || effective.data().length == 0) {
            try {
                effective = aeron.query(CoreMessageType.FUNDING_PROGRESS_QUERY, UUID.randomUUID(),
                        com.surprising.aeron.protocol.CoreStateQueryCodec.encodeFundingProgressQuery(instrumentId));
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Aeron funding progress unavailable", exception);
            }
        }
        if (effective == null || (effective.status() != com.surprising.aeron.protocol.ResponseStatus.OK
                && effective.commandStatus() != com.surprising.aeron.protocol.ResponseStatus.APPLIED)
                || effective.data().length == 0) {
            throw new IllegalStateException("Aeron funding progress unavailable");
        }
        CoreFundingProgressView progress = CoreFundingProgressCodec.decode(effective.data());
        if (progress.settlementId() != 0 && progress.settlementId() != settlement.settlementId()) {
            // The progress query returns the most recent settlement for this instrument.
            // A finished earlier funding interval does not belong to the new interval.
            if (progress.settlementId() > settlement.settlementId()) return progress;
            if (progress.complete()) return null;
            throw new IllegalStateException("Aeron funding settlement progress mismatch");
        }
        return progress;
    }

    public FundingRateResponse latestRate(String instrumentId) {
        return latestFundingRateCache.requireFresh(normalizeSymbol(instrumentId));
    }

    public FundingRateQueryResponse rateHistory(String instrumentId, int limit) {
        return rateHistory(instrumentId, limit, null, null);
    }

    public FundingRateQueryResponse rateHistory(String instrumentId, int limit, String cursor, String sort) {
        int capped = normalizeLimit(limit);
        var page = rateRepository.historyPage(normalizeSymbol(instrumentId), capped, cursor, sort);
        return new FundingRateQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public FundingSettlementResponse latestSettlement(String instrumentId) {
        return settlementRepository.latestCore(normalizeSymbol(instrumentId))
                .orElseThrow(() -> new java.util.NoSuchElementException("funding settlement not found for instrumentId: " + instrumentId));
    }

    public FundingPaymentQueryResponse payments(long userId, String instrumentId, int limit) {
        return payments(userId, instrumentId, limit, null, null);
    }

    public FundingPaymentQueryResponse payments(long userId, String instrumentId, int limit, String cursor, String sort) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        int capped = normalizeLimit(limit);
        String normalizedSymbol = instrumentId == null || instrumentId.isBlank() ? null : normalizeSymbol(instrumentId);
        var page = paymentRepository.corePage(userId, normalizedSymbol, capped, cursor, sort);
        return new FundingPaymentQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    private boolean ownsSymbol(String instrumentId) {
        return !properties.getCoordination().isEnabled()
                || leaseRepository.acquire(instrumentId, nodeId, properties.getCoordination().getLeaseDuration());
    }

    private PerpFundingRateEvent fundingRateEvent(FundingRateResponse rate) {
        return new PerpFundingRateEvent(rate.instrumentId(),
                new BigDecimal(FundingTime.rateDecimalString(rate.fundingRatePpm())), rate.fundingTime(),
                rate.fundingIntervalHours(), rate.sequence(), rate.eventTime());
    }

    private String normalizeSymbol(String instrumentId) {
        if (instrumentId == null || instrumentId.isBlank()) throw new IllegalArgumentException("instrumentId is required");
        String normalized = instrumentId.trim().toUpperCase(java.util.Locale.ROOT);
        if (!com.surprising.product.api.InstrumentIds.valid(normalized)) {
            throw new IllegalArgumentException("invalid instrumentId: " + instrumentId);
        }
        return normalized;
    }

    public record SettlementCycle(boolean enabled, int dueRates, int settledRates, int pages, int failedRates) {
        static SettlementCycle disabled() {
            return new SettlementCycle(false, 0, 0, 0, 0);
        }
    }

    private int normalizeLimit(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be in [1, 1000]");
        return limit;
    }

    private String resolveNodeId(String configured) {
        return configured == null || configured.isBlank() ? "funding-" + UUID.randomUUID() : configured.trim();
    }
}
