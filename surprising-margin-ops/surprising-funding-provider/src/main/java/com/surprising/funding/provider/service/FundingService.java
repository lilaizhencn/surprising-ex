package com.surprising.funding.provider.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.FundingSettlementAccountCommand;
import com.surprising.funding.api.model.FundingPaymentQueryResponse;
import com.surprising.funding.api.model.FundingRateQueryResponse;
import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.api.model.FundingSettlementResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingPaymentPage;
import com.surprising.funding.provider.model.FundingPaymentWrite;
import com.surprising.funding.provider.model.FundingSettlementWork;
import com.surprising.funding.provider.repository.FundingAccountCommandOutboxRepository;
import com.surprising.funding.provider.repository.FundingDueRateRepository;
import com.surprising.funding.provider.repository.FundingLeaseRepository;
import com.surprising.funding.provider.repository.FundingPaymentCandidateRepository;
import com.surprising.funding.provider.repository.FundingPaymentRepository;
import com.surprising.funding.provider.repository.FundingRateInputRepository;
import com.surprising.funding.provider.repository.FundingRateRepository;
import com.surprising.funding.provider.repository.FundingSequenceRepository;
import com.surprising.funding.provider.repository.FundingSettlementRepository;
import com.surprising.price.api.model.PerpFundingRateEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
    public class FundingService {

    private static final Logger log = LoggerFactory.getLogger(FundingService.class);

    private final FundingProperties properties;
    private final FundingLeaseRepository leaseRepository;
    private final FundingSequenceRepository sequenceRepository;
    private final FundingRateInputRepository rateInputRepository;
    private final FundingRateRepository rateRepository;
    private final FundingDueRateRepository dueRateRepository;
    private final FundingSettlementRepository settlementRepository;
    private final FundingPaymentCandidateRepository paymentCandidateRepository;
    private final FundingPaymentRepository paymentRepository;
    private final FundingAccountCommandOutboxRepository accountCommandOutboxRepository;
    private final LatestFundingRateCache latestFundingRateCache;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String nodeId;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public FundingService(FundingProperties properties,
                          FundingLeaseRepository leaseRepository,
                          FundingSequenceRepository sequenceRepository,
                          FundingRateInputRepository rateInputRepository,
                          FundingRateRepository rateRepository,
                          FundingDueRateRepository dueRateRepository,
                          FundingSettlementRepository settlementRepository,
                          FundingPaymentCandidateRepository paymentCandidateRepository,
                          FundingPaymentRepository paymentRepository,
                          FundingAccountCommandOutboxRepository accountCommandOutboxRepository,
                          LatestFundingRateCache latestFundingRateCache,
                          @Qualifier("fundingKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate,
                          tools.jackson.databind.ObjectMapper objectMapper,
                          PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.leaseRepository = leaseRepository;
        this.sequenceRepository = sequenceRepository;
        this.rateInputRepository = rateInputRepository;
        this.rateRepository = rateRepository;
        this.dueRateRepository = dueRateRepository;
        this.settlementRepository = settlementRepository;
        this.paymentCandidateRepository = paymentCandidateRepository;
        this.paymentRepository = paymentRepository;
        this.accountCommandOutboxRepository = accountCommandOutboxRepository;
        this.latestFundingRateCache = latestFundingRateCache;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.nodeId = resolveNodeId(properties.getCoordination().getNodeId());
    }

    public void publishRates() {
        if (!properties.getCalculation().isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        for (var input : rateInputRepository.find(properties.getCalculation().getMaxMarkAge())) {
            if (!ownsSymbol(input.symbol())) {
                continue;
            }
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

    public void settleDueRates() {
        if (!properties.getSettlement().isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        freezeDuePredictions(now);
        Deque<FundingSettlementWork> settlements = new ArrayDeque<>();
        for (FundingRateResponse rate : dueRateRepository.findDue(
                now, properties.getSettlement().getBatchSize())) {
            if (!ownsSymbol(rate.symbol())) {
                continue;
            }
            try {
                FundingSettlementWork settlement = transactionTemplate.execute(
                        status -> settlementRepository.createOrResume(rate, Instant.now()).orElse(null));
                if (settlement != null) {
                    settlements.addLast(settlement);
                }
            } catch (Exception ex) {
                log.error("Failed to create or resume funding settlement symbol={} fundingTime={}: {}",
                        rate.symbol(), rate.fundingTime(), ex.getMessage(), ex);
            }
        }
        int remainingPages = Math.max(1, properties.getSettlement().getMaxPagesPerRun());
        while (remainingPages > 0 && !settlements.isEmpty()) {
            FundingSettlementWork settlement = settlements.removeFirst();
            if (!ownsSymbol(settlement.symbol())) {
                continue;
            }
            try {
                Boolean completed = transactionTemplate.execute(
                        status -> settlePage(settlement.settlementId(), Instant.now()));
                remainingPages--;
                if (!Boolean.TRUE.equals(completed)) {
                    settlements.addLast(settlement);
                }
            } catch (Exception ex) {
                log.error("Failed to dispatch funding settlement page settlementId={} symbol={}: {}",
                        settlement.settlementId(), settlement.symbol(), ex.getMessage(), ex);
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
        var page = rateRepository.historyPage(normalizeSymbol(symbol), capped, cursor, sort);
        return new FundingRateQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public FundingSettlementResponse latestSettlement(String symbol) {
        return settlementRepository.latest(normalizeSymbol(symbol))
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
        var page = paymentRepository.page(userId, normalizedSymbol, capped, cursor, sort);
        return new FundingPaymentQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    private boolean settlePage(long settlementId, Instant now) {
        FundingSettlementWork settlement = settlementRepository
                .lockProcessing(settlementId)
                .orElse(null);
        if (settlement == null) {
            return true;
        }
        FundingPaymentPage page = paymentCandidateRepository.findPage(
                settlement, Math.max(1, properties.getSettlement().getPaymentPageSize()));
        List<FundingPaymentCandidate> payable = page.items().stream()
                .filter(payment -> payment.amountUnits() != 0L)
                .toList();
        List<FundingPaymentWrite> writes =
                paymentRepository.insert(settlementId, payable, now);
        List<FundingAccountCommandOutboxRepository.FundingAccountCommandWrite> commands =
                new ArrayList<>(writes.size());
        for (FundingPaymentWrite write : writes) {
            FundingPaymentCandidate payment = write.payment();
            FundingSettlementAccountCommand payload = new FundingSettlementAccountCommand(
                    settlementId, write.paymentId(), payment.symbol(), payment.marginMode(),
                    payment.positionSide(), payment.asset(), payment.signedQuantitySteps(),
                    payment.notionalUnits(), payment.fundingRatePpm(), payment.amountUnits());
            AccountUserCommand command = new AccountUserCommand(
                    AccountUserCommand.CURRENT_SCHEMA_VERSION,
                    write.commandId(),
                    properties.getKafka().getProductLine(),
                    payment.userId(),
                    AccountUserCommandType.FUNDING_SETTLE,
                    "FUNDING",
                    Long.toString(write.paymentId()),
                    null,
                    objectMapper.writeValueAsString(payload),
                    now,
                    null);
            commands.add(new FundingAccountCommandOutboxRepository.FundingAccountCommandWrite(
                    write.paymentId(), command));
        }
        accountCommandOutboxRepository.enqueueBatch(commands, now);
        settlementRepository.advancePage(settlementId, page, writes, now);
        return !page.hasMore();
    }

    private boolean ownsSymbol(String symbol) {
        if (!properties.getCoordination().isEnabled()) {
            return true;
        }
        return leaseRepository.acquire(symbol, nodeId, properties.getCoordination().getLeaseDuration());
    }

    private void freezeDuePredictions(Instant now) {
        for (FundingRateResponse rate : latestFundingRateCache.duePredictions(now)) {
            if (!ownsSymbol(rate.symbol())) {
                continue;
            }
            try {
                rateRepository.saveFinal(rate);
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
