package com.surprising.instrument.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.IndexSourceConfig;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.RiskLimitBracket;
import com.surprising.instrument.provider.repository.InstrumentCurrentVersionRepository;
import com.surprising.instrument.provider.repository.InstrumentIndexSourceRepository;
import com.surprising.instrument.provider.repository.InstrumentProductCurrentVersionRepository;
import com.surprising.instrument.provider.repository.InstrumentRepository;
import com.surprising.instrument.provider.repository.InstrumentRiskBracketRepository;
import com.surprising.instrument.provider.repository.InstrumentSequenceRepository;
import com.surprising.instrument.provider.repository.InstrumentVersionKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class InstrumentStorageServiceTest {

    private final InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
    private final InstrumentSequenceRepository sequenceRepository = mock(InstrumentSequenceRepository.class);
    private final InstrumentCurrentVersionRepository currentVersionRepository =
            mock(InstrumentCurrentVersionRepository.class);
    private final InstrumentProductCurrentVersionRepository productCurrentVersionRepository =
            mock(InstrumentProductCurrentVersionRepository.class);
    private final InstrumentRiskBracketRepository riskBracketRepository =
            mock(InstrumentRiskBracketRepository.class);
    private final InstrumentIndexSourceRepository indexSourceRepository =
            mock(InstrumentIndexSourceRepository.class);
    private final InstrumentStorageService storageService = new InstrumentStorageService(
            instrumentRepository,
            sequenceRepository,
            currentVersionRepository,
            productCurrentVersionRepository,
            riskBracketRepository,
            indexSourceRepository);

    @Test
    void nextVersionUsesMainTableOnlyForSequenceBootstrap() {
        when(instrumentRepository.maxVersion("BTC-USDT")).thenReturn(7L);
        when(sequenceRepository.next("BTC-USDT", 8L)).thenReturn(9L);

        assertThat(storageService.nextVersion("BTC-USDT")).isEqualTo(9L);

        verify(instrumentRepository).maxVersion("BTC-USDT");
        verify(sequenceRepository).next("BTC-USDT", 8L);
    }

    @Test
    void latestAggregatesSingleTableRepositories() {
        InstrumentResponse core = mock(InstrumentResponse.class);
        InstrumentVersionKey key = new InstrumentVersionKey("BTC-USDT", 3L);
        RiskLimitBracket bracket = mock(RiskLimitBracket.class);
        IndexSourceConfig source = mock(IndexSourceConfig.class);
        when(core.symbol()).thenReturn("BTC-USDT");
        when(core.version()).thenReturn(3L);
        when(currentVersionRepository.findVersion("BTC-USDT")).thenReturn(OptionalLong.of(3L));
        when(instrumentRepository.version("BTC-USDT", 3L)).thenReturn(Optional.of(core));
        when(riskBracketRepository.findAll(List.of(key))).thenReturn(Map.of(key, List.of(bracket)));
        when(indexSourceRepository.findAll(List.of(key))).thenReturn(Map.of(key, List.of(source)));

        InstrumentResponse response = storageService.latest("BTC-USDT").orElseThrow();

        assertThat(response.symbol()).isEqualTo("BTC-USDT");
        assertThat(response.version()).isEqualTo(3L);
        assertThat(response.riskLimitBrackets()).containsExactly(bracket);
        assertThat(response.indexSources()).containsExactly(source);
    }
}
