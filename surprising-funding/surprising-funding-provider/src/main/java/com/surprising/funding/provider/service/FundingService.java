package com.surprising.funding.provider.service;

import com.surprising.funding.api.model.FundingPaymentQueryResponse;
import com.surprising.funding.api.model.FundingRateQueryResponse;
import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.api.model.FundingSettlementResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.repository.FundingOutboxRepository;
import com.surprising.funding.provider.repository.FundingRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FundingService {

    private static final Logger log = LoggerFactory.getLogger(FundingService.class);

    private final FundingProperties properties;
    private final FundingRepository fundingRepository;
    private final FundingOutboxRepository outboxRepository;
    private final TransactionTemplate transactionTemplate;
    private final String nodeId;

    public FundingService(FundingProperties properties,
                          FundingRepository fundingRepository,
                          FundingOutboxRepository outboxRepository,
                          PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.fundingRepository = fundingRepository;
        this.outboxRepository = outboxRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.nodeId = resolveNodeId(properties.getCoordination().getNodeId());
    }

    @Transactional
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
            FundingRateResponse rate = fundingRepository.saveRate(input, sequence, fundingRate, fundingTime, now);
            outboxRepository.enqueue(properties.getKafka().getFundingRateTopic(), rate.symbol(), "FUNDING_RATE",
                    fundingRatePayload(rate), now);
        }
    }

    @Scheduled(fixedDelayString = "${surprising.funding.settlement.settle-delay-ms:1000}")
    public void settleDueRates() {
        if (!properties.getSettlement().isEnabled()) {
            return;
        }
        Instant now = Instant.now();
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
        return fundingRepository.latestRate(normalizeSymbol(symbol))
                .orElseThrow(() -> new IllegalStateException("funding rate not found for symbol: " + symbol));
    }

    public FundingRateQueryResponse rateHistory(String symbol, int limit) {
        int capped = Math.max(1, Math.min(1000, limit));
        List<FundingRateResponse> rows = fundingRepository.rateHistory(normalizeSymbol(symbol), capped);
        return new FundingRateQueryResponse(rows.size(), rows);
    }

    public FundingSettlementResponse latestSettlement(String symbol) {
        return fundingRepository.latestSettlement(normalizeSymbol(symbol))
                .orElseThrow(() -> new IllegalStateException("funding settlement not found for symbol: " + symbol));
    }

    public FundingPaymentQueryResponse payments(long userId, String symbol, int limit) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        int capped = Math.max(1, Math.min(1000, limit));
        String normalizedSymbol = symbol == null || symbol.isBlank() ? null : normalizeSymbol(symbol);
        var rows = fundingRepository.payments(userId, normalizedSymbol, capped);
        return new FundingPaymentQueryResponse(rows.size(), rows);
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

    private String fundingRatePayload(FundingRateResponse rate) {
        return "{"
                + "\"symbol\":\"" + rate.symbol() + "\","
                + "\"fundingRate\":" + FundingTime.rateDecimalString(rate.fundingRatePpm()) + ","
                + "\"nextFundingTime\":\"" + rate.fundingTime() + "\","
                + "\"fundingIntervalHours\":" + rate.fundingIntervalHours() + ","
                + "\"sequence\":" + rate.sequence() + ","
                + "\"eventTime\":\"" + rate.eventTime() + "\""
                + "}";
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

    private String resolveNodeId(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return "funding-" + UUID.randomUUID();
    }
}
