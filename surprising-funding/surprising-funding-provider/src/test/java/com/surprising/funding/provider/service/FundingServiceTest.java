package com.surprising.funding.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.funding.api.model.AdminCursorPage;
import com.surprising.funding.api.model.FundingPaymentResponse;
import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingRateInput;
import com.surprising.funding.provider.repository.FundingOutboxRepository;
import com.surprising.funding.provider.repository.FundingRepository;
import java.util.ArrayList;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class FundingServiceTest {

    @Test
    void publishRatesPropagatesOutboxFailure() {
        FakeFundingRepository fundingRepository = new FakeFundingRepository();
        FailingFundingOutboxRepository outboxRepository = new FailingFundingOutboxRepository();
        FundingService service = new FundingService(new FundingProperties(), fundingRepository, outboxRepository,
                transactionManager());

        assertThatThrownBy(service::publishRates)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox down");

        assertThat(fundingRepository.savedRates).isEqualTo(1);
        assertThat(outboxRepository.enqueued).isEqualTo(1);
    }

    @Test
    void publishRatesDoesNothingWhenCalculationIsDisabled() {
        FakeFundingRepository fundingRepository = new FakeFundingRepository();
        NoopFundingOutboxRepository outboxRepository = new NoopFundingOutboxRepository();
        FundingProperties properties = new FundingProperties();
        properties.getCalculation().setEnabled(false);
        FundingService service = new FundingService(properties, fundingRepository, outboxRepository,
                transactionManager());

        service.publishRates();

        assertThat(fundingRepository.rateInputCalls).isZero();
        assertThat(fundingRepository.savedRates).isZero();
    }

    @Test
    void settleDueRatesIsolatesFailureByFundingRate() {
        FakeFundingRepository fundingRepository = new FakeFundingRepository();
        fundingRepository.dueRates = List.of(
                rate("BTC-USDT", 11L),
                rate("ETH-USDT", 12L));
        fundingRepository.failPaymentSymbol = "BTC-USDT";
        FundingService service = new FundingService(new FundingProperties(), fundingRepository,
                new NoopFundingOutboxRepository(), transactionManager());

        service.settleDueRates();

        assertThat(fundingRepository.appliedPaymentSymbols).containsExactly("BTC-USDT", "ETH-USDT");
        assertThat(fundingRepository.completedSettlementIds).containsExactly(3012L);
    }

    @Test
    void settleDueRatesDoesNothingWhenSettlementIsDisabled() {
        FakeFundingRepository fundingRepository = new FakeFundingRepository();
        fundingRepository.dueRates = List.of(rate("BTC-USDT", 11L));
        FundingProperties properties = new FundingProperties();
        properties.getSettlement().setEnabled(false);
        FundingService service = new FundingService(properties, fundingRepository,
                new NoopFundingOutboxRepository(), transactionManager());

        service.settleDueRates();

        assertThat(fundingRepository.dueRateCalls).isZero();
        assertThat(fundingRepository.appliedPaymentSymbols).isEmpty();
        assertThat(fundingRepository.completedSettlementIds).isEmpty();
    }

    @Test
    void queryMethodsExposeCursorMetadata() {
        FakeFundingRepository fundingRepository = new FakeFundingRepository();
        FundingService service = new FundingService(new FundingProperties(), fundingRepository,
                new NoopFundingOutboxRepository(), transactionManager());

        var rates = service.rateHistory("btc-usdt", 50, "rate-cursor", "eventTime.asc");
        var payments = service.payments(1001L, "btc-usdt", 25, "payment-cursor", "createdAt.asc");

        assertThat(rates.rates()).hasSize(1);
        assertThat(rates.nextCursor()).isEqualTo("next-rate");
        assertThat(rates.hasMore()).isTrue();
        assertThat(rates.sort()).isEqualTo("eventTime.asc");
        assertThat(rates.limit()).isEqualTo(50);
        assertThat(payments.payments()).hasSize(1);
        assertThat(payments.nextCursor()).isEqualTo("next-payment");
        assertThat(payments.sort()).isEqualTo("createdAt.asc");
        assertThat(fundingRepository.lastRateCursor).isEqualTo("rate-cursor");
        assertThat(fundingRepository.lastPaymentCursor).isEqualTo("payment-cursor");
    }

    private static final class FakeFundingRepository extends FundingRepository {
        private int savedRates;
        private int rateInputCalls;
        private int dueRateCalls;
        private List<FundingRateResponse> dueRates = List.of();
        private String failPaymentSymbol;
        private String lastRateCursor;
        private String lastPaymentCursor;
        private final List<String> appliedPaymentSymbols = new ArrayList<>();
        private final List<Long> completedSettlementIds = new ArrayList<>();

        private FakeFundingRepository() {
            super(null);
        }

        @Override
        public boolean acquireLease(String symbol, String ownerId, Duration leaseDuration) {
            return true;
        }

        @Override
        public List<FundingRateInput> rateInputs(Duration maxMarkAge) {
            rateInputCalls++;
            return List.of(new FundingRateInput("BTC-USDT", 0L, 100L, 10L,
                    -3_750L, 3_750L, 8, Instant.parse("2026-07-01T00:00:00Z")));
        }

        @Override
        public long nextSymbolSequence(String symbol) {
            return 11L;
        }

        @Override
        public FundingRateResponse saveRate(FundingRateInput input,
                                            long sequence,
                                            long fundingRatePpm,
                                            Instant fundingTime,
                                            Instant now) {
            savedRates++;
            return new FundingRateResponse(input.symbol(), sequence, fundingRatePpm, input.premiumRatePpm(),
                    input.interestRatePpm(), fundingTime, input.fundingIntervalHours(), "PREDICTED", now);
        }

        @Override
        public List<FundingRateResponse> dueRates(Instant now, int limit) {
            dueRateCalls++;
            return dueRates;
        }

        @Override
        public AdminCursorPage.CursorPage<FundingRateResponse> rateHistoryPage(String symbol,
                                                                                int limit,
                                                                                String cursor,
                                                                                String sort) {
            lastRateCursor = cursor;
            return new AdminCursorPage.CursorPage<>(List.of(rate(symbol, 99L)), "next-rate", true,
                    sort, limit);
        }

        @Override
        public AdminCursorPage.CursorPage<FundingPaymentResponse> paymentsPage(long userId,
                                                                                String symbol,
                                                                                int limit,
                                                                                String cursor,
                                                                                String sort) {
            lastPaymentCursor = cursor;
            return new AdminCursorPage.CursorPage<>(List.of(new FundingPaymentResponse(
                    501L, 301L, userId, symbol, "USDT", 1L, 100L, 10L, -1L,
                    Instant.parse("2026-07-01T08:00:00Z"))), "next-payment", true, sort, limit);
        }

        @Override
        public Optional<Long> createSettlement(FundingRateResponse rate, Instant now) {
            return Optional.of(rate.sequence() + 3000L);
        }

        @Override
        public List<FundingPaymentCandidate> paymentCandidates(FundingRateResponse rate) {
            return List.of(new FundingPaymentCandidate(1001L, rate.symbol(), "USDT",
                    10L, 1_000L, rate.fundingRatePpm(), -1L));
        }

        @Override
        public boolean insertPayment(long settlementId, FundingPaymentCandidate payment, Instant now) {
            return true;
        }

        @Override
        public void applyPaymentToAccount(long settlementId, FundingPaymentCandidate payment, Instant now) {
            appliedPaymentSymbols.add(payment.symbol());
            if (payment.symbol().equals(failPaymentSymbol)) {
                throw new IllegalStateException("account settlement failed");
            }
        }

        @Override
        public void completeSettlement(long settlementId,
                                       long totalLongPaymentUnits,
                                       long totalShortPaymentUnits,
                                       int positionCount,
                                       Instant now) {
            completedSettlementIds.add(settlementId);
        }
    }

    private static final class FailingFundingOutboxRepository extends FundingOutboxRepository {
        private int enqueued;

        private FailingFundingOutboxRepository() {
            super(null, null);
        }

        @Override
        public void enqueue(String topic, String eventKey, String eventType, String payload, Instant now) {
            enqueued++;
            throw new IllegalStateException("outbox down");
        }
    }

    private static final class NoopFundingOutboxRepository extends FundingOutboxRepository {
        private NoopFundingOutboxRepository() {
            super(null, null);
        }
    }

    private static FundingRateResponse rate(String symbol, long sequence) {
        Instant fundingTime = Instant.parse("2026-07-01T08:00:00Z");
        return new FundingRateResponse(symbol, sequence, 100L, 90L, 10L,
                fundingTime, 8, "PREDICTED", fundingTime.minusSeconds(10));
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
