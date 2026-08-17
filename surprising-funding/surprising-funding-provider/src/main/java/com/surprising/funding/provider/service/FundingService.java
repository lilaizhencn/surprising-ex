package com.surprising.funding.provider.service;

import com.surprising.aeron.protocol.ApplyFundingCommand;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class FundingService {

    private static final Logger log = LoggerFactory.getLogger(FundingService.class);

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
            if (!ownsSymbol(input.symbol())) continue;
            long sequence = sequenceRepository.next(input.symbol());
            long rawRate = Math.addExact(input.interestRatePpm(), input.premiumRatePpm());
            long fundingRate = FundingMath.clampRate(rawRate, input.fundingRateFloorPpm(), input.fundingRateCapPpm());
            Instant fundingTime = FundingTime.nextFundingTime(now, input.fundingIntervalHours());
            FundingRateResponse rate = new FundingRateResponse(input.symbol(), sequence, fundingRate,
                    input.premiumRatePpm(), input.interestRatePpm(), fundingTime, input.fundingIntervalHours(),
                    "PREDICTED", now);
            latestFundingRateCache.update(rate);
            kafkaTemplate.send(properties.getKafka().getFundingRateTopic(), rate.symbol(), fundingRateEvent(rate));
        }
    }

    public synchronized SettlementCycle settleDueRates() {
        if (!properties.getSettlement().isEnabled()) return SettlementCycle.disabled();
        Instant now = Instant.now();
        int dueRates = 0;
        int settledRates = 0;
        int pages = 0;
        int failedRates = 0;
        for (FundingRateResponse rate : latestFundingRateCache.duePredictions(now).stream()
                .limit(properties.getSettlement().getBatchSize()).toList()) {
            dueRates++;
            if (!ownsSymbol(rate.symbol())) continue;
            try {
                FundingSettlementRepository.CoreSettlement settlement = settlementRepository.reserveCore(rate);
                String commandPrefix = properties.getKafka().getProductLine() + ":funding:"
                        + rate.symbol() + ':' + settlement.settlementId();
                long cursor = 0;
                CoreFundingProgressView persisted = decodeProgressOrQuery(rate.symbol(), settlement, null);
                if (persisted != null && persisted.complete()
                        && persisted.settlementId() == settlement.settlementId()) {
                    rateRepository.saveFinal(rate);
                    latestFundingRateCache.removeIfCurrent(rate);
                    continue;
                }
                if (persisted != null && !persisted.complete()) {
                    cursor = persisted.nextCursorUserId();
                }
                boolean complete = false;
                for (int page = 0; page < properties.getSettlement().getMaxPagesPerRun(); page++) {
                    UUID commandId = UUID.nameUUIDFromBytes((commandPrefix + ':' + cursor)
                            .getBytes(StandardCharsets.UTF_8));
                    CoreResponse response = aeron.commandWithResponse(CoreMessageType.APPLY_FUNDING, commandId,
                            TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(
                                    settlement.settlementId(), rate.symbol(), settlement.instrumentVersion(),
                                    rate.fundingRatePpm(), cursor, ApplyFundingCommand.DEFAULT_MAX_USERS)));
                    pages++;
                    CoreFundingProgressView progress = decodeProgressOrQuery(rate.symbol(), settlement, response);
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
                rateRepository.saveFinal(rate);
                latestFundingRateCache.removeIfCurrent(rate);
                settledRates++;
            } catch (Exception exception) {
                failedRates++;
                log.error("Aeron funding settlement failed symbol={} fundingTime={}: {}",
                        rate.symbol(), rate.fundingTime(), exception.getMessage(), exception);
            }
        }
        return new SettlementCycle(true, dueRates, settledRates, pages, failedRates);
    }

    private CoreFundingProgressView decodeProgressOrQuery(
            String symbol,
            FundingSettlementRepository.CoreSettlement settlement,
            CoreResponse response) {
        CoreResponse effective = response;
        if (effective == null || effective.data().length == 0) {
            try {
                effective = aeron.query(CoreMessageType.FUNDING_PROGRESS_QUERY, UUID.randomUUID(),
                        com.surprising.aeron.protocol.CoreStateQueryCodec.encodeFundingProgressQuery(symbol));
            } catch (RuntimeException exception) {
                return null;
            }
        }
        if (effective == null || (effective.status() != com.surprising.aeron.protocol.ResponseStatus.OK
                && effective.commandStatus() != com.surprising.aeron.protocol.ResponseStatus.APPLIED)
                || effective.data().length == 0) {
            return null;
        }
        CoreFundingProgressView progress = CoreFundingProgressCodec.decode(effective.data());
        if (progress.settlementId() != 0 && progress.settlementId() != settlement.settlementId()) {
            throw new IllegalStateException("Aeron funding settlement progress mismatch");
        }
        return progress;
    }

    public FundingRateResponse latestRate(String symbol) {
        return latestFundingRateCache.requireFresh(normalizeSymbol(symbol));
    }

    public FundingRateQueryResponse rateHistory(String symbol, int limit) {
        return rateHistory(symbol, limit, null, null);
    }

    public FundingRateQueryResponse rateHistory(String symbol, int limit, String cursor, String sort) {
        int capped = normalizeLimit(limit);
        var page = rateRepository.historyPage(normalizeSymbol(symbol), capped, cursor, sort);
        return new FundingRateQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public FundingSettlementResponse latestSettlement(String symbol) {
        return settlementRepository.latestCore(normalizeSymbol(symbol))
                .orElseThrow(() -> new IllegalStateException("funding settlement not found for symbol: " + symbol));
    }

    public FundingPaymentQueryResponse payments(long userId, String symbol, int limit) {
        return payments(userId, symbol, limit, null, null);
    }

    public FundingPaymentQueryResponse payments(long userId, String symbol, int limit, String cursor, String sort) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        int capped = normalizeLimit(limit);
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        var page = paymentRepository.corePage(userId, normalizedSymbol, capped, cursor, sort);
        return new FundingPaymentQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    private boolean ownsSymbol(String symbol) {
        return !properties.getCoordination().isEnabled()
                || leaseRepository.acquire(symbol, nodeId, properties.getCoordination().getLeaseDuration());
    }

    private PerpFundingRateEvent fundingRateEvent(FundingRateResponse rate) {
        return new PerpFundingRateEvent(rate.symbol(),
                new BigDecimal(FundingTime.rateDecimalString(rate.fundingRatePpm())), rate.fundingTime(),
                rate.fundingIntervalHours(), rate.sequence(), rate.eventTime());
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol is required");
        String normalized = symbol.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
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
