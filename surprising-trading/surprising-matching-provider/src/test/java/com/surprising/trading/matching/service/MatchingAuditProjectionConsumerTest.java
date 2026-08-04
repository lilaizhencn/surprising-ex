package com.surprising.trading.matching.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.surprising.trading.api.model.MatchResultEvent;
import com.surprising.trading.api.model.OrderCommandType;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.repository.MatchingResultRepository;
import com.surprising.trading.matching.repository.MatchingTradeRepository;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MatchingAuditProjectionConsumerTest {

    @Test
    void projectsMatchResultAndTradesToTheAsyncAuditRepositories() throws Exception {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(com.surprising.product.api.ProductLine.LINEAR_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);
        MatchingResultRepository resultRepository = org.mockito.Mockito.mock(MatchingResultRepository.class);
        MatchingTradeRepository tradeRepository = org.mockito.Mockito.mock(MatchingTradeRepository.class);
        MatchingAuditProjectionConsumer consumer = new MatchingAuditProjectionConsumer(
                new ObjectMapper(), properties, resultRepository, tradeRepository);
        MatchResultEvent result = new MatchResultEvent(11L, 12L, 13L, "BTC-USDT", 1L,
                OrderCommandType.PLACE, "SUCCESS", 0L, OrderStatus.ACCEPTED,
                Instant.parse("2026-07-01T00:00:00Z"), List.of());

        consumer.onMatchResult(new ConsumerRecord<>(properties.getKafka().getMatchResultsTopic(), 0, 0L,
                "BTC-USDT", new ObjectMapper().writeValueAsString(result)));

        verify(resultRepository).save(any(MatchResultEvent.class));
        verify(tradeRepository).saveBatch(List.of());
    }
}
