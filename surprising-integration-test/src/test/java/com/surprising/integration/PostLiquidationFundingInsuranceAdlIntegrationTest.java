package com.surprising.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.adl.api.model.AdlEventResponse;
import com.surprising.adl.api.model.AdlSide;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.model.AdlCandidate;
import com.surprising.adl.provider.model.DeficitRow;
import com.surprising.adl.provider.repository.AdlRepository;
import com.surprising.adl.provider.service.AdlMath;
import com.surprising.adl.provider.service.AdlService;
import com.surprising.funding.api.model.FundingPaymentResponse;
import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.api.model.FundingSettlementResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingBalanceState;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.repository.FundingOutboxRepository;
import com.surprising.funding.provider.repository.FundingRepository;
import com.surprising.funding.provider.service.FundingMath;
import com.surprising.funding.provider.service.FundingService;
import com.surprising.insurance.api.model.InsuranceCoverageResponse;
import com.surprising.insurance.api.model.InsuranceFundBalanceResponse;
import com.surprising.insurance.api.model.InsuranceFundLedgerResponse;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.insurance.provider.repository.InsuranceRepository;
import com.surprising.insurance.provider.service.InsuranceMath;
import com.surprising.insurance.provider.service.InsuranceService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PostLiquidationFundingInsuranceAdlIntegrationTest {

    private static final String SYMBOL = "BTC-USDT";
    private static final String ASSET = "USDT";
    private static final Instant FUNDING_TIME = Instant.parse("2026-07-01T08:00:00Z");

    @Test
    void fundingSettlementMovesUnitsAcrossAccountBalancesAndPositionMargin() {
        SharedState state = new SharedState();
        state.putBalance(1001L, 50L, 400L, 0L);
        state.putBalance(2002L, 10L, 400L, 0L);
        state.putPosition(1001L, 10L, 100L, 400L);
        state.putPosition(2002L, -10L, 100L, 400L);

        FakeFundingRepository repository = new FakeFundingRepository(state);
        FundingService service = new FundingService(new FundingProperties(), repository,
                new NoopFundingOutboxRepository(), transactionManager());

        service.settleDueRates();

        assertThat(state.balance(1001L)).isEqualTo(new BalanceState(0L, 0L, 550L));
        assertThat(state.positionMargin(1001L)).isZero();
        assertThat(state.balance(2002L)).isEqualTo(new BalanceState(1_010L, 400L, 0L));
        assertThat(state.positionMargin(2002L)).isEqualTo(400L);
        assertThat(repository.payments).extracting(FundingPaymentResponse::amountUnits)
                .containsExactly(-1_000L, 1_000L);
        assertThat(repository.settlements).singleElement()
                .satisfies(settlement -> {
                    assertThat(settlement.totalLongPaymentUnits()).isEqualTo(-1_000L);
                    assertThat(settlement.totalShortPaymentUnits()).isEqualTo(1_000L);
                    assertThat(settlement.positionCount()).isEqualTo(2);
                    assertThat(settlement.status()).isEqualTo("COMPLETED");
                });
    }

    @Test
    void insuranceCoversFirstAndAdlConsumesResidualProfitablePositionWhenFundIsEmpty() {
        SharedState state = new SharedState();
        state.putBalance(9009L, 0L, 0L, 900L);
        state.insuranceFunds.put(ASSET, 400L);
        state.putBalance(7007L, 0L, 100L, 0L);
        state.putPosition(7007L, 10L, 100L, 100L);
        state.markPriceTicks = 200L;

        InsuranceService insuranceService = new InsuranceService(new InsuranceProperties(),
                new FakeInsuranceRepository(state));
        insuranceService.coverDeficits();

        assertThat(state.insuranceFunds.get(ASSET)).isZero();
        assertThat(state.balance(9009L)).isEqualTo(new BalanceState(0L, 0L, 500L));
        assertThat(state.coverages).singleElement()
                .satisfies(coverage -> {
                    assertThat(coverage.coveredUnits()).isEqualTo(400L);
                    assertThat(coverage.remainingDeficitUnits()).isEqualTo(500L);
                    assertThat(coverage.status()).isEqualTo("PARTIALLY_COVERED");
                });

        AdlProperties adlProperties = new AdlProperties();
        adlProperties.getScanner().setMinDeficitAgeMs(0L);
        AdlService adlService = new AdlService(adlProperties, new FakeAdlRepository(state));
        adlService.processResidualDeficits();

        assertThat(state.balance(9009L)).isEqualTo(new BalanceState(0L, 0L, 0L));
        assertThat(state.balance(7007L)).isEqualTo(new BalanceState(50L, 50L, 0L));
        PositionState targetPosition = state.positions.get(7007L);
        assertThat(targetPosition.signedQuantitySteps).isEqualTo(5L);
        assertThat(targetPosition.realizedPnlUnits).isEqualTo(500L);
        assertThat(state.positionMargin(7007L)).isEqualTo(50L);
        assertThat(state.adlEvents).singleElement()
                .satisfies(event -> {
                    assertThat(event.deficitUserId()).isEqualTo(9009L);
                    assertThat(event.targetUserId()).isEqualTo(7007L);
                    assertThat(event.closedQuantitySteps()).isEqualTo(5L);
                    assertThat(event.realizedProfitUnits()).isEqualTo(500L);
                    assertThat(event.coveredUnits()).isEqualTo(500L);
                    assertThat(event.remainingDeficitUnits()).isZero();
                });
    }

    private static final class FakeFundingRepository extends FundingRepository {
        private final SharedState state;
        private final Map<String, FundingPaymentResponse> paymentKeys = new LinkedHashMap<>();
        private final List<FundingPaymentResponse> payments = new ArrayList<>();
        private final List<FundingSettlementResponse> settlements = new ArrayList<>();

        private FakeFundingRepository(SharedState state) {
            super(null);
            this.state = state;
        }

        @Override
        public boolean acquireLease(String symbol, String ownerId, Duration leaseDuration) {
            return true;
        }

        @Override
        public List<FundingRateResponse> dueRates(Instant now, int limit) {
            return List.of(new FundingRateResponse(SYMBOL, 1L, 100_000L, 100_000L, 0L,
                    FUNDING_TIME, 8, "PREDICTED", FUNDING_TIME.minusSeconds(1)));
        }

        @Override
        public Optional<Long> createSettlement(FundingRateResponse rate, Instant now) {
            return Optional.of(1L);
        }

        @Override
        public List<FundingPaymentCandidate> paymentCandidates(FundingRateResponse rate) {
            return state.positions.entrySet().stream()
                    .map(entry -> {
                        long notionalUnits = Math.abs(entry.getValue().signedQuantitySteps) * 1_000L;
                        return new FundingPaymentCandidate(entry.getKey(), SYMBOL, ASSET,
                                entry.getValue().signedQuantitySteps, notionalUnits, rate.fundingRatePpm(),
                                FundingMath.paymentAmount(entry.getValue().signedQuantitySteps, notionalUnits,
                                        rate.fundingRatePpm()));
                    })
                    .sorted(Comparator.comparingLong(FundingPaymentCandidate::userId))
                    .toList();
        }

        @Override
        public boolean insertPayment(long settlementId, FundingPaymentCandidate payment, Instant now) {
            String key = settlementId + ":" + payment.userId();
            if (paymentKeys.containsKey(key)) {
                return false;
            }
            FundingPaymentResponse response = new FundingPaymentResponse(state.next("funding-payment"), settlementId,
                    payment.userId(), payment.symbol(), payment.asset(), payment.signedQuantitySteps(),
                    payment.notionalUnits(), payment.fundingRatePpm(), payment.amountUnits(), now);
            paymentKeys.put(key, response);
            payments.add(response);
            return true;
        }

        @Override
        public void applyPaymentToAccount(long settlementId, FundingPaymentCandidate payment, Instant now) {
            BalanceState current = state.balance(payment.userId());
            long positionMargin = state.positionMargin(payment.userId());
            FundingBalanceState next = FundingMath.applyPayment(current.availableUnits, current.lockedUnits,
                    current.deficitUnits, payment.amountUnits(), positionMargin);
            long lockedDebit = current.lockedUnits - next.lockedUnits();
            if (lockedDebit > 0) {
                state.setPositionMargin(payment.userId(), positionMargin - lockedDebit);
            }
            state.putBalance(payment.userId(), next.availableUnits(), next.lockedUnits(), next.deficitUnits());
        }

        @Override
        public void completeSettlement(long settlementId,
                                       long totalLongPaymentUnits,
                                       long totalShortPaymentUnits,
                                       int positionCount,
                                       Instant now) {
            settlements.add(new FundingSettlementResponse(settlementId, SYMBOL, FUNDING_TIME, 100_000L,
                    totalLongPaymentUnits, totalShortPaymentUnits, positionCount, "COMPLETED", now, now));
        }
    }

    private static final class NoopFundingOutboxRepository extends FundingOutboxRepository {
        private NoopFundingOutboxRepository() {
            super(null, null);
        }
    }

    private static final class FakeInsuranceRepository extends InsuranceRepository {
        private final SharedState state;

        private FakeInsuranceRepository(SharedState state) {
            super(null);
            this.state = state;
        }

        @Override
        public InsuranceFundBalanceResponse adjustFund(String asset, long amountUnits, String referenceId,
                                                       String reason) {
            long next = Math.addExact(state.insuranceFunds.getOrDefault(asset, 0L), amountUnits);
            if (next < 0) {
                throw new IllegalArgumentException("insufficient insurance fund balance");
            }
            state.insuranceFunds.put(asset, next);
            return new InsuranceFundBalanceResponse(asset, next, Instant.now());
        }

        @Override
        public int coverDeficits(int batchSize) {
            int coveredRows = 0;
            for (Map.Entry<Long, BalanceState> entry : state.balances.entrySet()) {
                if (coveredRows >= batchSize || entry.getValue().deficitUnits <= 0) {
                    continue;
                }
                long fund = state.insuranceFunds.getOrDefault(ASSET, 0L);
                long covered = InsuranceMath.coverAmount(entry.getValue().deficitUnits, fund);
                if (covered <= 0) {
                    continue;
                }
                long remaining = entry.getValue().deficitUnits - covered;
                state.insuranceFunds.put(ASSET, fund - covered);
                state.putBalance(entry.getKey(), entry.getValue().availableUnits, entry.getValue().lockedUnits,
                        remaining);
                long coverageId = state.next("insurance-coverage");
                state.coverages.add(new InsuranceCoverageResponse(coverageId, entry.getKey(), ASSET,
                        entry.getValue().deficitUnits, covered, remaining,
                        remaining == 0L ? "COVERED" : "PARTIALLY_COVERED", "DEFICIT_COVERAGE",
                        Instant.now(), Instant.now()));
                state.insuranceLedger.add(new InsuranceFundLedgerResponse(state.next("insurance-ledger"), ASSET,
                        -covered, fund - covered, "DEFICIT_COVERAGE", String.valueOf(coverageId),
                        "COVER_ACCOUNT_DEFICIT", Instant.now()));
                coveredRows++;
            }
            return coveredRows;
        }

        @Override
        public List<InsuranceCoverageResponse> coverages(Long userId, String asset, int limit) {
            return state.coverages.stream()
                    .filter(coverage -> userId == null || coverage.userId() == userId)
                    .limit(limit)
                    .toList();
        }
    }

    private static final class FakeAdlRepository extends AdlRepository {
        private final SharedState state;

        private FakeAdlRepository(SharedState state) {
            super(null);
            this.state = state;
        }

        @Override
        public List<DeficitRow> claimResidualDeficits(int batchSize, Duration minAge) {
            if (state.insuranceFunds.getOrDefault(ASSET, 0L) > 0L) {
                return List.of();
            }
            return state.balances.entrySet().stream()
                    .filter(entry -> entry.getValue().deficitUnits > 0L)
                    .limit(batchSize)
                    .map(entry -> new DeficitRow(entry.getKey(), ASSET, entry.getValue().deficitUnits))
                    .toList();
        }

        @Override
        public List<AdlCandidate> queue(String asset, long excludedUserId, int limit, Duration maxMarkAge) {
            return state.positions.entrySet().stream()
                    .filter(entry -> entry.getKey() != excludedUserId)
                    .map(entry -> candidate(entry.getKey(), entry.getValue()))
                    .filter(candidate -> candidate.unrealizedProfitUnits() > 0L)
                    .sorted(Comparator.comparingLong(AdlCandidate::priorityScorePpm).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<AdlCandidate> lockCandidate(long userId, String symbol, String asset, Duration maxMarkAge) {
            PositionState position = state.positions.get(userId);
            if (position == null || !SYMBOL.equals(symbol) || !ASSET.equals(asset)) {
                return Optional.empty();
            }
            AdlCandidate candidate = candidate(userId, position);
            return candidate.unrealizedProfitUnits() > 0L ? Optional.of(candidate) : Optional.empty();
        }

        @Override
        public long executeAdl(DeficitRow deficit, AdlCandidate candidate, long remainingDeficitUnits) {
            long closeSteps = AdlMath.closeStepsForCover(remainingDeficitUnits, candidate.absQuantitySteps(),
                    candidate.unrealizedProfitUnits());
            long realizedProfitUnits = AdlMath.proportionalUnits(candidate.unrealizedProfitUnits(), closeSteps,
                    candidate.absQuantitySteps());
            long coveredUnits = Math.min(remainingDeficitUnits, realizedProfitUnits);
            long remaining = remainingDeficitUnits - coveredUnits;
            long releasedMargin = AdlMath.proportionalUnits(state.positionMargin(candidate.userId()), closeSteps,
                    candidate.absQuantitySteps());

            PositionState position = state.positions.get(candidate.userId());
            long nextAbs = candidate.absQuantitySteps() - closeSteps;
            long nextSigned = position.signedQuantitySteps > 0L ? nextAbs : -nextAbs;
            state.positions.put(candidate.userId(), new PositionState(nextSigned,
                    nextAbs == 0L ? 0L : position.entryPriceTicks,
                    position.realizedPnlUnits + realizedProfitUnits,
                    state.positionMargin(candidate.userId()) - releasedMargin));

            BalanceState targetBalance = state.balance(candidate.userId());
            long targetAvailable = targetBalance.availableUnits + releasedMargin + realizedProfitUnits - coveredUnits;
            long targetLocked = targetBalance.lockedUnits - releasedMargin;
            state.putBalance(candidate.userId(), targetAvailable, targetLocked, targetBalance.deficitUnits);

            BalanceState deficitBalance = state.balance(deficit.userId());
            state.putBalance(deficit.userId(), deficitBalance.availableUnits, deficitBalance.lockedUnits, remaining);
            long eventId = state.next("adl-event");
            state.adlEvents.add(new AdlEventResponse(eventId, deficit.userId(), candidate.userId(), ASSET,
                    SYMBOL, candidate.side(), closeSteps, candidate.entryPriceTicks(), candidate.markPriceTicks(),
                    deficit.deficitUnits(), realizedProfitUnits, coveredUnits, remaining,
                    candidate.priorityScorePpm(), "ADL_DEFICIT_COVERAGE", Instant.now()));
            return remaining;
        }

        @Override
        public List<AdlEventResponse> events(Long userId, String asset, String symbol, int limit) {
            return state.adlEvents.stream()
                    .filter(event -> userId == null
                            || event.deficitUserId() == userId
                            || event.targetUserId() == userId)
                    .limit(limit)
                    .toList();
        }

        private AdlCandidate candidate(long userId, PositionState position) {
            long absQuantity = Math.abs(position.signedQuantitySteps);
            long profitTicksPerStep = position.signedQuantitySteps > 0
                    ? state.markPriceTicks - position.entryPriceTicks
                    : position.entryPriceTicks - state.markPriceTicks;
            long unrealizedProfit = Math.max(0L, profitTicksPerStep * absQuantity);
            long notional = state.markPriceTicks * absQuantity;
            long margin = state.positionMargin(userId);
            long profitRate = AdlMath.profitRatePpm(unrealizedProfit, notional);
            long leverage = AdlMath.effectiveLeveragePpm(notional, margin);
            return new AdlCandidate(userId, ASSET, SYMBOL,
                    position.signedQuantitySteps > 0 ? AdlSide.LONG : AdlSide.SHORT,
                    position.signedQuantitySteps, absQuantity, position.entryPriceTicks, state.markPriceTicks,
                    profitTicksPerStep, notional, unrealizedProfit, margin, profitRate, leverage,
                    AdlMath.priorityScorePpm(profitRate, leverage));
        }
    }

    private static final class SharedState {
        private final Map<String, Long> sequences = new HashMap<>();
        private final Map<Long, BalanceState> balances = new LinkedHashMap<>();
        private final Map<Long, PositionState> positions = new LinkedHashMap<>();
        private final Map<String, Long> insuranceFunds = new HashMap<>();
        private final List<InsuranceCoverageResponse> coverages = new ArrayList<>();
        private final List<InsuranceFundLedgerResponse> insuranceLedger = new ArrayList<>();
        private final List<AdlEventResponse> adlEvents = new ArrayList<>();
        private long markPriceTicks = 100L;

        private long next(String sequenceName) {
            long value = sequences.getOrDefault(sequenceName, 0L) + 1L;
            sequences.put(sequenceName, value);
            return value;
        }

        private void putBalance(long userId, long availableUnits, long lockedUnits, long deficitUnits) {
            balances.put(userId, new BalanceState(availableUnits, lockedUnits, deficitUnits));
        }

        private BalanceState balance(long userId) {
            return balances.computeIfAbsent(userId, ignored -> new BalanceState(0L, 0L, 0L));
        }

        private void putPosition(long userId, long signedQuantitySteps, long entryPriceTicks, long marginUnits) {
            positions.put(userId, new PositionState(signedQuantitySteps, entryPriceTicks, 0L, marginUnits));
        }

        private long positionMargin(long userId) {
            PositionState position = positions.get(userId);
            return position == null ? 0L : position.marginUnits;
        }

        private void setPositionMargin(long userId, long marginUnits) {
            PositionState position = positions.get(userId);
            positions.put(userId, new PositionState(position.signedQuantitySteps, position.entryPriceTicks,
                    position.realizedPnlUnits, marginUnits));
        }
    }

    private record BalanceState(long availableUnits, long lockedUnits, long deficitUnits) {
    }

    private static final class PositionState {
        private final long signedQuantitySteps;
        private final long entryPriceTicks;
        private final long realizedPnlUnits;
        private final long marginUnits;

        private PositionState(long signedQuantitySteps,
                              long entryPriceTicks,
                              long realizedPnlUnits,
                              long marginUnits) {
            this.signedQuantitySteps = signedQuantitySteps;
            this.entryPriceTicks = entryPriceTicks;
            this.realizedPnlUnits = realizedPnlUnits;
            this.marginUnits = marginUnits;
        }
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
