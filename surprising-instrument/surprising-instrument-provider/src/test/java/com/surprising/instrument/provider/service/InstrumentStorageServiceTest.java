package com.surprising.instrument.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.IndexSourceConfig;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.RiskLimitBracket;
import com.surprising.instrument.provider.repository.InstrumentIndexSourceRepository;
import com.surprising.instrument.provider.repository.InstrumentRepository;
import com.surprising.instrument.provider.repository.InstrumentRiskBracketRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class InstrumentStorageServiceTest {

    private final InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
    private final InstrumentRiskBracketRepository riskBracketRepository = mock(InstrumentRiskBracketRepository.class);
    private final InstrumentIndexSourceRepository indexSourceRepository = mock(InstrumentIndexSourceRepository.class);
    private final InstrumentStorageService storageService = new InstrumentStorageService(instrumentRepository,
            mock(com.surprising.instrument.provider.repository.InstrumentChangeLogRepository.class),
            riskBracketRepository, indexSourceRepository, null,
            tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build());

    @Test
    void latestAggregatesSingleTableRepositories() {
        InstrumentResponse core = mock(InstrumentResponse.class);
        var key = new com.surprising.instrument.provider.repository.InstrumentKey(com.surprising.product.api.ProductLine.LINEAR_PERPETUAL, "BTC-USDT");
        RiskLimitBracket bracket = mock(RiskLimitBracket.class);
        IndexSourceConfig source = mock(IndexSourceConfig.class);
        when(core.symbol()).thenReturn("BTC-USDT");
        when(core.changeId()).thenReturn(3L);
        when(core.contractType()).thenReturn(com.surprising.instrument.api.model.ContractType.LINEAR_PERPETUAL);
        when(instrumentRepository.current("BTC-USDT", null)).thenReturn(Optional.of(core));
        when(riskBracketRepository.findAll(List.of(key))).thenReturn(Map.of(key, List.of(bracket)));
        when(indexSourceRepository.findAll(List.of(key))).thenReturn(Map.of(key, List.of(source)));

        InstrumentResponse response = storageService.latest("BTC-USDT").orElseThrow();

        assertThat(response.symbol()).isEqualTo("BTC-USDT");
        assertThat(response.changeId()).isEqualTo(3L);
        assertThat(response.riskLimitBrackets()).containsExactly(bracket);
        assertThat(response.indexSources()).containsExactly(source);
    }
}
