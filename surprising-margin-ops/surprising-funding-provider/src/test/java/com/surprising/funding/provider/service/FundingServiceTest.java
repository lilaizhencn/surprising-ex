package com.surprising.funding.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingRateInput;
import com.surprising.funding.provider.repository.FundingRepository;
import com.surprising.funding.provider.repository.FundingAccountCommandOutboxRepository;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

class FundingServiceTest {

    @Test
    void publishesPredictedFundingDirectlyToKafkaWithoutWritingRateTicksOrOutbox() {
        FundingProperties properties = new FundingProperties();
        FakeFundingRepository repository = new FakeFundingRepository();
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        FundingService service = service(properties, repository, kafka);

        service.publishRates();

        ArgumentCaptor<PerpFundingRateEvent> event = ArgumentCaptor.forClass(PerpFundingRateEvent.class);
        verify(kafka).send(eq(properties.getKafka().getFundingRateTopic()), eq("BTC-USDT"), event.capture());
        assertThat(repository.finalized).isEmpty();
        assertThat(event.getValue().fundingRate()).isEqualByComparingTo("0.000110");
        assertThat(service.latestRate("btc-usdt").status()).isEqualTo("PREDICTED");
    }

    @Test
    void freezesOnlyDuePredictionBeforeSettlementReadsFinalRows() {
        FundingProperties properties = new FundingProperties();
        FakeFundingRepository repository = new FakeFundingRepository();
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        LatestFundingRateCache cache = new LatestFundingRateCache(properties);
        FundingRateResponse due = new FundingRateResponse("BTC-USDT", 11L, 110L, 100L, 10L,
                Instant.now().minusSeconds(1), 8, "PREDICTED", Instant.now());
        cache.update(due);
        cache.update(new FundingRateResponse("BTC-USDT", 12L, 120L, 100L, 20L,
                Instant.now().plusSeconds(8 * 60 * 60), 8, "PREDICTED", Instant.now()));
        FundingService service = new FundingService(properties, repository,
                mock(FundingAccountCommandOutboxRepository.class), cache, kafka,
                new ObjectMapper(), transactionManager());

        service.settleDueRates();

        assertThat(repository.finalized).containsExactly(due);
        assertThat(repository.dueRateCalls).isEqualTo(1);
        assertThat(service.latestRate("BTC-USDT").sequence()).isEqualTo(12L);
    }

    @Test
    void doesNotPublishWhenCalculationIsDisabled() {
        FundingProperties properties = new FundingProperties();
        properties.getCalculation().setEnabled(false);
        FakeFundingRepository repository = new FakeFundingRepository();
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);

        service(properties, repository, kafka).publishRates();

        assertThat(repository.rateInputCalls).isZero();
        verify(kafka, never()).send(any(), any(), any());
    }

    @Test
    void dispatchesFundingPaymentsInOneShortBatchPage() {
        FundingProperties properties = new FundingProperties();
        properties.getSettlement().setPaymentPageSize(2);
        properties.getSettlement().setMaxPagesPerRun(1);
        FakeFundingRepository repository = new FakeFundingRepository();
        FundingRateResponse rate = new FundingRateResponse("BTC-USDT", 11L, 100L, 90L, 10L,
                Instant.now().minusSeconds(1), 8, "FINAL", Instant.now());
        repository.dueRates = List.of(rate);
        FundingPaymentCandidate longPayment = new FundingPaymentCandidate(
                1001L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET, "USDT",
                10L, 100_000L, 100L, -10L);
        FundingPaymentCandidate shortPayment = new FundingPaymentCandidate(
                1002L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET, "USDT",
                -10L, 100_000L, 100L, 10L);
        repository.pages = List.of(new FundingRepository.FundingPaymentPage(
                List.of(longPayment, shortPayment),
                FundingRepository.FundingPaymentCursor.from(shortPayment), false));
        FundingAccountCommandOutboxRepository outbox = mock(FundingAccountCommandOutboxRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        FundingService service = new FundingService(properties, repository, outbox,
                new LatestFundingRateCache(properties), kafka, new ObjectMapper(), transactionManager);

        service.settleDueRates();

        assertThat(repository.pageCalls).isEqualTo(1);
        assertThat(repository.requestedPageSize).isEqualTo(2);
        assertThat(repository.insertedPayments).containsExactly(longPayment, shortPayment);
        assertThat(repository.advanceCalls).isEqualTo(1);
        assertThat(transactionManager.commits).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<FundingAccountCommandOutboxRepository.FundingAccountCommandWrite>> commands =
                ArgumentCaptor.forClass(List.class);
        verify(outbox).enqueueBatch(commands.capture(), any(Instant.class));
        assertThat(commands.getValue()).hasSize(2);
        assertThat(commands.getValue()).extracting(item -> item.command().partitionKey())
                .containsExactly("LINEAR_PERPETUAL:1001", "LINEAR_PERPETUAL:1002");
    }

    private FundingService service(FundingProperties properties,
                                   FakeFundingRepository repository,
                                   KafkaTemplate<String, Object> kafka) {
        return new FundingService(properties, repository, mock(FundingAccountCommandOutboxRepository.class),
                new LatestFundingRateCache(properties), kafka, new ObjectMapper(), transactionManager());
    }

    private static final class FakeFundingRepository extends FundingRepository {
        private int rateInputCalls;
        private int dueRateCalls;
        private final java.util.ArrayList<FundingRateResponse> finalized = new java.util.ArrayList<>();
        private List<FundingRateResponse> dueRates = List.of();
        private List<FundingRepository.FundingPaymentPage> pages = List.of();
        private int pageCalls;
        private int requestedPageSize;
        private int advanceCalls;
        private List<FundingPaymentCandidate> insertedPayments = List.of();
        private final FundingRepository.FundingSettlementWork settlement =
                new FundingRepository.FundingSettlementWork(
                        77L, "BTC-USDT", Instant.parse("2026-07-01T00:00:00Z"),
                        100L, 7L, 65_000L, new FundingRepository.FundingPaymentCursor(0L, "", ""));

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
                    -3_750L, 3_750L, 8, Instant.now()));
        }

        @Override
        public long nextSymbolSequence(String symbol) {
            return 11L;
        }

        @Override
        public boolean saveFinalRate(FundingRateResponse rate) {
            finalized.add(rate);
            return true;
        }

        @Override
        public List<FundingRateResponse> dueRates(Instant now, int limit) {
            dueRateCalls++;
            return dueRates;
        }

        @Override
        public Optional<FundingRepository.FundingSettlementWork> createOrResumeSettlement(
                FundingRateResponse rate, Instant now) {
            return Optional.of(settlement);
        }

        @Override
        public Optional<FundingRepository.FundingSettlementWork> lockProcessingSettlement(long settlementId) {
            return Optional.of(settlement);
        }

        @Override
        public FundingRepository.FundingPaymentPage paymentCandidatesPage(
                FundingRepository.FundingSettlementWork settlement, int limit) {
            requestedPageSize = limit;
            return pages.get(pageCalls++);
        }

        @Override
        public List<FundingRepository.FundingPaymentWrite> insertPayments(
                long settlementId, List<FundingPaymentCandidate> payments, Instant now) {
            insertedPayments = List.copyOf(payments);
            java.util.ArrayList<FundingRepository.FundingPaymentWrite> writes = new java.util.ArrayList<>();
            long paymentId = 100L;
            for (FundingPaymentCandidate payment : payments) {
                writes.add(new FundingRepository.FundingPaymentWrite(
                        paymentId, "FUNDING:LINEAR_PERPETUAL:77:" + paymentId, payment));
                paymentId++;
            }
            return writes;
        }

        @Override
        public void advanceSettlementPage(long settlementId,
                                          FundingRepository.FundingPaymentPage page,
                                          List<FundingRepository.FundingPaymentWrite> writes,
                                          Instant now) {
            advanceCalls++;
        }
    }

    private static PlatformTransactionManager transactionManager() {
        return new TrackingTransactionManager();
    }

    private static final class TrackingTransactionManager implements PlatformTransactionManager {
        private int commits;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commits++;
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
