package com.surprising.funding.provider.service;

import com.surprising.funding.api.model.FundingPaymentQueryResponse;
import com.surprising.funding.api.model.FundingRateQueryResponse;
import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.api.model.FundingSettlementResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.repository.FundingRepository;
import com.surprising.price.api.model.PerpFundingRateEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FundingService {

    private static final Logger log = LoggerFactory.getLogger(FundingService.class);

    private final FundingProperties properties;
    private final FundingRepository fundingRepository;
    private final LatestFundingRateCache latestFundingRateCache;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String nodeId;

    public FundingService(FundingProperties properties,
                          FundingRepository fundingRepository,
                          LatestFundingRateCache latestFundingRateCache,
                          @Qualifier("fundingKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
                          PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.fundingRepository = fundingRepository;
        this.latestFundingRateCache = latestFundingRateCache;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.nodeId = resolveNodeId(properties.getCoordination().getNodeId());
    }

    @Scheduled(fixedDelayString = "${surprising.funding.calculation.publish-delay-ms:1000}")
    public void publishRates() {
        if (!properties.getCalculation().isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        for (var input : fundingRepository.rateInputs(properties.getCalculation().getMaxMarkAge())) {
            if (!ownsSymbol(input.symbol())) {
                continue;
            }
            long sequence = fundingRepository.nextSymbolSequence(input.symbol());
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

    @Scheduled(fixedDelayString = "${surprising.funding.settlement.settle-delay-ms:1000}")
    public void settleDueRates() {
        if (!properties.getSettlement().isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        freezeDuePredictions(now);
        for (FundingRateResponse rate : fundingRepository.dueRates(now, properties.getSettlement().getBatchSize())) {
            if (!ownsSymbol(rate.symbol())) {
                continue;
            }
            try {
                transactionTemplate.executeWithoutResult(status -> settleRate(rate, Instant.now()));
            } catch (Exception ex) {
                log.error("Failed to settle funding rate symbol={} fundingTime={}: {}",
                        rate.symbol(), rate.fundingTime(), ex.getMessage(), ex);
            }
        }
    }

    public FundingRateResponse latestRate(String symbol) {
        return latestFundingRateCache.requireFresh(normalizeSymbol(symbol));
    }

    public FundingRateQueryResponse rateHistory(String symbol, int limit) {
        return rateHistory(symbol, limit, null, null);
    }

    public FundingRateQueryResponse rateHistory(String symbol, int limit, String cursor, String sort) {
        int capped = normalizeLimit(limit);
        var page = fundingRepository.rateHistoryPage(normalizeSymbol(symbol), capped, cursor, sort);
        return new FundingRateQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public FundingSettlementResponse latestSettlement(String symbol) {
        return fundingRepository.latestSettlement(normalizeSymbol(symbol))
                .orElseThrow(() -> new IllegalStateException("funding settlement not found for symbol: " + symbol));
    }

    public FundingPaymentQueryResponse payments(long userId, String symbol, int limit) {
        return payments(userId, symbol, limit, null, null);
    }

    public FundingPaymentQueryResponse payments(long userId, String symbol, int limit, String cursor, String sort) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        int capped = normalizeLimit(limit);
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        var page = fundingRepository.paymentsPage(userId, normalizedSymbol, capped, cursor, sort);
        return new FundingPaymentQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    private void settleRate(FundingRateResponse rate, Instant now) {
        var settlementId = fundingRepository.createSettlement(rate, now);
        if (settlementId.isEmpty()) {
            return;
        }
        long totalLongPayment = 0L;
        long totalShortPayment = 0L;
        int positionCount = 0;
        for (FundingPaymentCandidate payment : fundingRepository.paymentCandidates(rate)) {
            if (payment.amountUnits() == 0L) {
                continue;
            }
            if (!fundingRepository.insertPayment(settlementId.get(), payment, now)) {
                continue;
            }
            fundingRepository.applyPaymentToAccount(settlementId.get(), payment, now);
            if (payment.signedQuantitySteps() > 0) {
                totalLongPayment = Math.addExact(totalLongPayment, payment.amountUnits());
            } else {
                totalShortPayment = Math.addExact(totalShortPayment, payment.amountUnits());
            }
            positionCount++;
        }
        fundingRepository.completeSettlement(settlementId.get(), totalLongPayment, totalShortPayment, positionCount, now);
    }

    private boolean ownsSymbol(String symbol) {
        if (!properties.getCoordination().isEnabled()) {
            return true;
        }
        return fundingRepository.acquireLease(symbol, nodeId, properties.getCoordination().getLeaseDuration());
    }

    private void freezeDuePredictions(Instant now) {
        for (FundingRateResponse rate : latestFundingRateCache.duePredictions(now)) {
            if (!ownsSymbol(rate.symbol())) {
                continue;
            }
            try {
                fundingRepository.saveFinalRate(rate);
                latestFundingRateCache.removeIfCurrent(rate);
            } catch (Exception ex) {
                log.error("Failed to freeze funding rate symbol={} fundingTime={}: {}",
                        rate.symbol(), rate.fundingTime(), ex.getMessage(), ex);
            }
        }
    }

    private PerpFundingRateEvent fundingRateEvent(FundingRateResponse rate) {
        return new PerpFundingRateEvent(rate.symbol(), new BigDecimal(FundingTime.rateDecimalString(rate.fundingRatePpm())),
                rate.fundingTime(), rate.fundingIntervalHours(), rate.sequence(), rate.eventTime());
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        String normalized = symbol.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return normalized;
    }

    private int normalizeLimit(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        return limit;
    }

    private String resolveNodeId(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return "funding-" + UUID.randomUUID();
    }
}
