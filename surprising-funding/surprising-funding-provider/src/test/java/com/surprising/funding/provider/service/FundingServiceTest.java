package com.surprising.funding.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.CoreFundingProgressCodec;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingRateInput;
import com.surprising.funding.provider.repository.FundingLeaseRepository;
import com.surprising.funding.provider.repository.FundingPaymentRepository;
import com.surprising.funding.provider.repository.FundingRateInputRepository;
import com.surprising.funding.provider.repository.FundingRateRepository;
import com.surprising.funding.provider.repository.FundingSequenceRepository;
import com.surprising.funding.provider.repository.FundingSettlementRepository;
import com.surprising.price.api.model.PerpFundingRateEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class FundingServiceTest {

    @Test
    void publishesPredictedFundingDirectlyToKafka() {
        FundingProperties properties = new FundingProperties();
        Fixture fixture = new Fixture(properties);
        when(fixture.rateInputRepository.find(properties.getCalculation().getMaxMarkAge()))
                .thenReturn(List.of(rateInput()));
        when(fixture.sequenceRepository.next("BTC-USDT")).thenReturn(11L);

        fixture.service.publishRates();

        ArgumentCaptor<PerpFundingRateEvent> event = ArgumentCaptor.forClass(PerpFundingRateEvent.class);
        verify(fixture.kafka).send(eq(properties.getKafka().getFundingRateTopic()), eq("BTC-USDT"), event.capture());
        assertThat(event.getValue().fundingRate()).isEqualByComparingTo("0.000110");
        assertThat(fixture.service.latestRate("btc-usdt").status()).isEqualTo("PREDICTED");
    }

    @Test
    void submitsOneDeterministicAeronCommandForDueRate() {
        FundingProperties properties = new FundingProperties();
        Fixture fixture = new Fixture(properties);
        Instant fundingTime = Instant.parse("2026-08-13T12:00:00Z");
        FundingRateResponse due = new FundingRateResponse("BTC-USDT", 11, 100, 90, 10,
                fundingTime, 8, "PREDICTED", Instant.now());
        fixture.cache.update(due);
        when(fixture.settlementRepository.reserveCore(due))
                .thenReturn(new FundingSettlementRepository.CoreSettlement(fundingTime.toEpochMilli(), 7));
        when(fixture.aeron.commandWithResponse(eq(CoreMessageType.APPLY_FUNDING), any(), any()))
                .thenReturn(new CoreResponse(ResponseStatus.APPLIED, 1, 0,
                        CoreFundingProgressCodec.encode(new CoreFundingProgressView(
                                fundingTime.toEpochMilli(), true, 0, 0))));

        fixture.service.settleDueRates();

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(fixture.aeron).commandWithResponse(eq(CoreMessageType.APPLY_FUNDING), any(), payload.capture());
        assertThat(TradingCommandCodec.decodeApplyFunding(payload.getValue())).isEqualTo(
                new ApplyFundingCommand(fundingTime.toEpochMilli(), "BTC-USDT", 7, 100));
        verify(fixture.rateRepository).saveFinal(due);
    }

    @Test
    void resumesFundingFromCoreCursorAndSavesAfterFinalChunk() {
        FundingProperties properties = new FundingProperties();
        Fixture fixture = new Fixture(properties);
        Instant fundingTime = Instant.parse("2026-08-13T12:00:00Z");
        FundingRateResponse due = new FundingRateResponse("BTC-USDT", 11, 100, 90, 10,
                fundingTime, 8, "PREDICTED", Instant.now());
        fixture.cache.update(due);
        long settlementId = fundingTime.toEpochMilli();
        when(fixture.settlementRepository.reserveCore(due))
                .thenReturn(new FundingSettlementRepository.CoreSettlement(settlementId, 7));
        when(fixture.aeron.query(eq(CoreMessageType.FUNDING_PROGRESS_QUERY), any(), any()))
                .thenReturn(new CoreResponse(ResponseStatus.OK, 0, 0,
                        CoreFundingProgressCodec.encode(new CoreFundingProgressView(
                                settlementId, false, 42, 0))));
        when(fixture.aeron.commandWithResponse(eq(CoreMessageType.APPLY_FUNDING), any(), any()))
                .thenReturn(new CoreResponse(ResponseStatus.APPLIED, 1, 0,
                                CoreFundingProgressCodec.encode(new CoreFundingProgressView(
                                        settlementId, true, 0, 3))));

        fixture.service.settleDueRates();

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(fixture.aeron).commandWithResponse(eq(CoreMessageType.APPLY_FUNDING), any(), payload.capture());
        assertThat(TradingCommandCodec.decodeApplyFunding(payload.getValue()).cursorUserId()).isEqualTo(42);
        verify(fixture.rateRepository).saveFinal(due);
    }

    @Test
    void keepsDueRateWhenAeronFailsSoSameCommandCanRetry() {
        FundingProperties properties = new FundingProperties();
        Fixture fixture = new Fixture(properties);
        Instant fundingTime = Instant.now().minusSeconds(1);
        FundingRateResponse due = new FundingRateResponse("BTC-USDT", 11, 100, 90, 10,
                fundingTime, 8, "PREDICTED", Instant.now());
        fixture.cache.update(due);
        when(fixture.settlementRepository.reserveCore(due))
                .thenReturn(new FundingSettlementRepository.CoreSettlement(fundingTime.toEpochMilli(), 7));
        doThrow(new IllegalStateException("cluster unavailable")).when(fixture.aeron)
                .commandWithResponse(eq(CoreMessageType.APPLY_FUNDING), any(), any());

        fixture.service.settleDueRates();

        assertThat(fixture.service.latestRate("BTC-USDT")).isEqualTo(due);
        verify(fixture.rateRepository, never()).saveFinal(any());
    }

    @Test
    void doesNotSettleWhenDisabled() {
        FundingProperties properties = new FundingProperties();
        properties.getSettlement().setEnabled(false);
        Fixture fixture = new Fixture(properties);

        fixture.service.settleDueRates();

        verify(fixture.aeron, never()).commandWithResponse(any(), any(), any());
    }

    private static FundingRateInput rateInput() {
        return new FundingRateInput("BTC-USDT", 0, 100, 10, -3_750, 3_750, 8, Instant.now());
    }

    private static final class Fixture {
        private final FundingLeaseRepository leaseRepository = mock(FundingLeaseRepository.class);
        private final FundingSequenceRepository sequenceRepository = mock(FundingSequenceRepository.class);
        private final FundingRateInputRepository rateInputRepository = mock(FundingRateInputRepository.class);
        private final FundingRateRepository rateRepository = mock(FundingRateRepository.class);
        private final FundingSettlementRepository settlementRepository = mock(FundingSettlementRepository.class);
        private final FundingPaymentRepository paymentRepository = mock(FundingPaymentRepository.class);
        private final FundingAeronGateway aeron = mock(FundingAeronGateway.class);
        @SuppressWarnings("unchecked")
        private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        private final LatestFundingRateCache cache;
        private final FundingService service;

        private Fixture(FundingProperties properties) {
            when(leaseRepository.acquire(any(), any(), any())).thenReturn(true);
            cache = new LatestFundingRateCache(properties);
            service = new FundingService(properties, leaseRepository, sequenceRepository, rateInputRepository,
                    rateRepository, settlementRepository, paymentRepository, cache, kafka, aeron);
        }
    }
}
