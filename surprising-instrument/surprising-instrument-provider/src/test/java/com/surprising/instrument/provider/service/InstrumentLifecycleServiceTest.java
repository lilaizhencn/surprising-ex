package com.surprising.instrument.provider.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.ContractSettlementMethod;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.OptionExerciseStyle;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.instrument.provider.config.InstrumentProperties;
import com.surprising.instrument.provider.repository.InstrumentRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InstrumentLifecycleServiceTest {

    @Test
    void advancesExpiredAndSettledContractsIndependently() {
        InstrumentRepository repository = mock(InstrumentRepository.class);
        InstrumentService instrumentService = mock(InstrumentService.class);
        InstrumentProperties properties = new InstrumentProperties();
        properties.getLifecycle().setBatchSize(3);
        InstrumentResponse expired = delivery("BTC-USDT-260327", InstrumentStatus.TRADING);
        InstrumentResponse settling = option("BTC-USDT-260327-50000-C", InstrumentStatus.SETTLING);
        InstrumentResponse closed = option("BTC-USDT-260327-50000-C", InstrumentStatus.CLOSED);
        when(repository.expiringContractsDue(any(Instant.class), eq(3))).thenReturn(List.of(expired));
        when(repository.settlingContractsDue(any(Instant.class), eq(3))).thenReturn(List.of(settling));
        when(instrumentService.updateStatus("BTC-USDT-260327", InstrumentStatus.SETTLING)).thenReturn(expired);
        when(instrumentService.updateStatus("BTC-USDT-260327-50000-C", InstrumentStatus.CLOSED)).thenReturn(closed);

        new InstrumentLifecycleService(repository, instrumentService, properties).advanceLifecycle();

        verify(instrumentService).updateStatus("BTC-USDT-260327", InstrumentStatus.SETTLING);
        verify(instrumentService).updateStatus("BTC-USDT-260327-50000-C", InstrumentStatus.CLOSED);
        verify(instrumentService).publishProductLifecycleEvent(closed);
    }

    @Test
    void skipsLifecycleWhenDisabled() {
        InstrumentRepository repository = mock(InstrumentRepository.class);
        InstrumentService instrumentService = mock(InstrumentService.class);
        InstrumentProperties properties = new InstrumentProperties();
        properties.getLifecycle().setEnabled(false);

        new InstrumentLifecycleService(repository, instrumentService, properties).advanceLifecycle();

        verify(repository, never()).expiringContractsDue(any(Instant.class), org.mockito.ArgumentMatchers.anyInt());
        verify(repository, never()).settlingContractsDue(any(Instant.class), org.mockito.ArgumentMatchers.anyInt());
    }

    private InstrumentResponse delivery(String symbol, InstrumentStatus status) {
        return response(symbol, InstrumentType.DELIVERY, ContractType.LINEAR_DELIVERY,
                null, null, null, status);
    }

    private InstrumentResponse option(String symbol, InstrumentStatus status) {
        return response(symbol, InstrumentType.OPTION, ContractType.VANILLA_OPTION,
                "BTC-USDT", 50_000_000_000L, OptionType.CALL, status);
    }

    private InstrumentResponse response(String symbol,
                                        InstrumentType instrumentType,
                                        ContractType contractType,
                                        String underlyingSymbol,
                                        Long strikePriceUnits,
                                        OptionType optionType,
                                        InstrumentStatus status) {
        Instant now = Instant.parse("2026-03-27T08:05:00Z");
        return new InstrumentResponse(
                symbol,
                2L,
                instrumentType,
                contractType,
                "BTC",
                "USDT",
                "USDT",
                1_000_000L,
                "USDT",
                10_000_000L,
                100_000L,
                1L,
                100_000L,
                500_000_000L,
                1_000_000_000_000_000L,
                10_000L,
                1,
                3,
                List.of("LIMIT"),
                List.of("GTC", "IOC"),
                true,
                true,
                false,
                100_000_000L,
                10_000L,
                5_000L,
                200L,
                500L,
                500_000_000_000_000L,
                300_000L,
                25_000_000_000_000L,
                0,
                0L,
                0L,
                0L,
                1_000_000_000_000L,
                2,
                Instant.parse("2026-03-27T08:00:00Z"),
                Instant.parse("2026-03-27T08:05:00Z"),
                underlyingSymbol,
                strikePriceUnits,
                optionType,
                optionType == null ? null : OptionExerciseStyle.EUROPEAN,
                ContractSettlementMethod.CASH,
                status,
                now.minusSeconds(600),
                now.minusSeconds(600),
                now,
                List.of(),
                List.of());
    }
}
