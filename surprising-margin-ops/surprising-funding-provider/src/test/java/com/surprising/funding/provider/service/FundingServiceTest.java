package com.surprising.funding.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingRateInput;
import com.surprising.funding.provider.repository.FundingRepository;
import com.surprising.price.api.model.PerpFundingRateEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
        FundingService service = new FundingService(properties, repository, cache, kafka, transactionManager());

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

    private FundingService service(FundingProperties properties,
                                   FakeFundingRepository repository,
                                   KafkaTemplate<String, Object> kafka) {
        return new FundingService(properties, repository, new LatestFundingRateCache(properties), kafka,
                transactionManager());
    }

    private static final class FakeFundingRepository extends FundingRepository {
        private int rateInputCalls;
        private int dueRateCalls;
        private final java.util.ArrayList<FundingRateResponse> finalized = new java.util.ArrayList<>();

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
            return List.of();
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
