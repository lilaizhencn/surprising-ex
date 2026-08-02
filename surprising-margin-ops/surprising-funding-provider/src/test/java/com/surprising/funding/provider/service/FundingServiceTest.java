package com.surprising.funding.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingPaymentCursor;
import com.surprising.funding.provider.model.FundingPaymentPage;
import com.surprising.funding.provider.model.FundingPaymentWrite;
import com.surprising.funding.provider.model.FundingRateInput;
import com.surprising.funding.provider.model.FundingSettlementWork;
import com.surprising.funding.provider.repository.FundingDueRateRepository;
import com.surprising.funding.provider.repository.FundingLeaseRepository;
import com.surprising.funding.provider.repository.FundingPaymentCandidateRepository;
import com.surprising.funding.provider.repository.FundingPaymentRepository;
import com.surprising.funding.provider.repository.FundingRateInputRepository;
import com.surprising.funding.provider.repository.FundingRateRepository;
import com.surprising.funding.provider.repository.FundingSequenceRepository;
import com.surprising.funding.provider.repository.FundingSettlementRepository;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
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
        Fixture fixture = new Fixture(properties, transactionManager());
        when(fixture.rateInputRepository.find(properties.getCalculation().getMaxMarkAge()))
                .thenReturn(List.of(rateInput()));
        when(fixture.sequenceRepository.next("BTC-USDT")).thenReturn(11L);

        fixture.service.publishRates();

        ArgumentCaptor<PerpFundingRateEvent> event = ArgumentCaptor.forClass(PerpFundingRateEvent.class);
        verify(fixture.kafka).send(eq(properties.getKafka().getFundingRateTopic()), eq("BTC-USDT"), event.capture());
        verify(fixture.rateRepository, never()).saveFinal(any());
        assertThat(event.getValue().fundingRate()).isEqualByComparingTo("0.000110");
        assertThat(fixture.service.latestRate("btc-usdt").status()).isEqualTo("PREDICTED");
    }

    @Test
    void freezesOnlyDuePredictionBeforeSettlementReadsFinalRows() {
        FundingProperties properties = new FundingProperties();
        Fixture fixture = new Fixture(properties, transactionManager());
        FundingRateResponse due = new FundingRateResponse("BTC-USDT", 11L, 110L, 100L, 10L,
                Instant.now().minusSeconds(1), 8, "PREDICTED", Instant.now());
        fixture.cache.update(due);
        fixture.cache.update(new FundingRateResponse("BTC-USDT", 12L, 120L, 100L, 20L,
                Instant.now().plusSeconds(8 * 60 * 60), 8, "PREDICTED", Instant.now()));
        when(fixture.rateRepository.saveFinal(due)).thenReturn(true);
        when(fixture.dueRateRepository.findDue(any(Instant.class),
                eq(properties.getSettlement().getBatchSize()))).thenReturn(List.of());

        fixture.service.settleDueRates();

        verify(fixture.rateRepository).saveFinal(due);
        verify(fixture.dueRateRepository).findDue(any(Instant.class),
                eq(properties.getSettlement().getBatchSize()));
        assertThat(fixture.service.latestRate("BTC-USDT").sequence()).isEqualTo(12L);
    }

    @Test
    void doesNotPublishWhenCalculationIsDisabled() {
        FundingProperties properties = new FundingProperties();
        properties.getCalculation().setEnabled(false);
        Fixture fixture = new Fixture(properties, transactionManager());

        fixture.service.publishRates();

        verify(fixture.rateInputRepository, never()).find(any());
        verify(fixture.kafka, never()).send(any(), any(), any());
    }

    @Test
    void dispatchesFundingPaymentsInOneShortBatchPage() {
        FundingProperties properties = new FundingProperties();
        properties.getSettlement().setPaymentPageSize(2);
        properties.getSettlement().setMaxPagesPerRun(1);
        TrackingTransactionManager transactionManager = new TrackingTransactionManager();
        Fixture fixture = new Fixture(properties, transactionManager);
        FundingRateResponse rate = new FundingRateResponse("BTC-USDT", 11L, 100L, 90L, 10L,
                Instant.now().minusSeconds(1), 8, "FINAL", Instant.now());
        FundingPaymentCandidate longPayment = new FundingPaymentCandidate(
                1001L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET, "USDT",
                10L, 100_000L, 100L, -10L);
        FundingPaymentCandidate shortPayment = new FundingPaymentCandidate(
                1002L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET, "USDT",
                -10L, 100_000L, 100L, 10L);
        FundingSettlementWork settlement = new FundingSettlementWork(
                77L, "BTC-USDT", Instant.parse("2026-07-01T00:00:00Z"),
                100L, 7L, 65_000L, new FundingPaymentCursor(0L, "", ""));
        FundingPaymentPage page = new FundingPaymentPage(
                List.of(longPayment, shortPayment), FundingPaymentCursor.from(shortPayment), false);
        List<FundingPaymentWrite> writes = List.of(
                new FundingPaymentWrite(100L, "FUNDING:LINEAR_PERPETUAL:77:100", longPayment),
                new FundingPaymentWrite(101L, "FUNDING:LINEAR_PERPETUAL:77:101", shortPayment));
        when(fixture.dueRateRepository.findDue(any(Instant.class), any(Integer.class)))
                .thenReturn(List.of(rate));
        when(fixture.settlementRepository.createOrResume(eq(rate), any(Instant.class)))
                .thenReturn(Optional.of(settlement));
        when(fixture.settlementRepository.lockProcessing(77L)).thenReturn(Optional.of(settlement));
        when(fixture.paymentCandidateRepository.findPage(settlement, 2)).thenReturn(page);
        when(fixture.paymentRepository.insert(eq(77L), eq(List.of(longPayment, shortPayment)), any(Instant.class)))
                .thenReturn(writes);

        fixture.service.settleDueRates();

        verify(fixture.paymentCandidateRepository).findPage(settlement, 2);
        verify(fixture.paymentRepository).insert(eq(77L), eq(List.of(longPayment, shortPayment)),
                any(Instant.class));
        verify(fixture.settlementRepository).advancePage(eq(77L), eq(page), eq(writes), any(Instant.class));
        assertThat(transactionManager.commits).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.surprising.account.api.model.AccountUserCommand>> commands =
                ArgumentCaptor.forClass(List.class);
        verify(fixture.accountCommandWalService).append(commands.capture());
        assertThat(commands.getValue()).hasSize(2);
        assertThat(commands.getValue()).extracting(item -> item.partitionKey())
                .containsExactly("LINEAR_PERPETUAL:1001", "LINEAR_PERPETUAL:1002");
    }

    private static FundingRateInput rateInput() {
        return new FundingRateInput("BTC-USDT", 0L, 100L, 10L,
                -3_750L, 3_750L, 8, Instant.now());
    }

    private static PlatformTransactionManager transactionManager() {
        return new TrackingTransactionManager();
    }

    private static final class Fixture {
        private final FundingLeaseRepository leaseRepository = mock(FundingLeaseRepository.class);
        private final FundingSequenceRepository sequenceRepository = mock(FundingSequenceRepository.class);
        private final FundingRateInputRepository rateInputRepository = mock(FundingRateInputRepository.class);
        private final FundingRateRepository rateRepository = mock(FundingRateRepository.class);
        private final FundingDueRateRepository dueRateRepository = mock(FundingDueRateRepository.class);
        private final FundingSettlementRepository settlementRepository = mock(FundingSettlementRepository.class);
        private final FundingPaymentCandidateRepository paymentCandidateRepository =
                mock(FundingPaymentCandidateRepository.class);
        private final FundingPaymentRepository paymentRepository = mock(FundingPaymentRepository.class);
        private final FundingAccountCommandWalService accountCommandWalService =
                mock(FundingAccountCommandWalService.class);
        @SuppressWarnings("unchecked")
        private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        private final LatestFundingRateCache cache;
        private final FundingService service;

        private Fixture(FundingProperties properties, PlatformTransactionManager transactionManager) {
            when(leaseRepository.acquire(any(), any(), any())).thenReturn(true);
            cache = new LatestFundingRateCache(properties);
            service = new FundingService(properties, leaseRepository, sequenceRepository,
                    rateInputRepository, rateRepository, dueRateRepository, settlementRepository,
                    paymentCandidateRepository, paymentRepository, accountCommandWalService, cache, kafka,
                    new ObjectMapper(), transactionManager);
        }
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
