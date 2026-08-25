package com.surprising.price.mark.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.api.model.PricePublishedEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceEncoding;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PublicTradeEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

class MarkPriceServiceTest {

    @Test
    void doesNotPublishMarkPriceWithoutFreshIndexPrice() throws Exception {
        MarkPriceCoordinationService coordinationService = mock(MarkPriceCoordinationService.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        MarkPriceService service = service(coordinationService, kafkaTemplate);
        ObjectMapper objectMapper = new ObjectMapper();
        Instant now = Instant.now();

        service.onBookTicker(objectMapper.writeValueAsString(
                new PerpBookTickerEvent("BTC-USDT", new BigDecimal("100.00"), new BigDecimal("100.10"), 1, now)));
        service.acceptTrade(new PerpTradeEvent("BTC-USDT", "t1", 1, now,
                new BigDecimal("100.05"), BigDecimal.ONE, "BUY"));
        service.acceptIndexPrice(new IndexPriceEvent("BTC-USDT", null, 1, PriceStatus.INSUFFICIENT_SOURCES, 3, 1,
                BigDecimal.valueOf(3), now, List.of()));
        service.publishMarkPrices();

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void doesNotPublishMarkPriceWhenIndexStatusIsNotUsable() throws Exception {
        MarkPriceCoordinationService coordinationService = mock(MarkPriceCoordinationService.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        MarkPriceService service = service(coordinationService, kafkaTemplate);
        ObjectMapper objectMapper = new ObjectMapper();
        Instant now = Instant.now();

        service.onBookTicker(objectMapper.writeValueAsString(
                new PerpBookTickerEvent("BTC-USDT", new BigDecimal("100.00"), new BigDecimal("100.10"), 1, now)));
        service.acceptTrade(new PerpTradeEvent("BTC-USDT", "t1", 1, now,
                new BigDecimal("100.05"), BigDecimal.ONE, "BUY"));
        service.acceptIndexPrice(new IndexPriceEvent("BTC-USDT", new BigDecimal("100.00"), 1,
                PriceStatus.INSUFFICIENT_SOURCES, 3, 1, BigDecimal.valueOf(3), now, List.of()));
        service.publishMarkPrices();

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void insufficientIndexPriceReplacesPreviouslyHealthyIndexPrice() throws Exception {
        MarkPriceCoordinationService coordinationService = mock(MarkPriceCoordinationService.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        when(coordinationService.nextSequence("price-mark", "BTC-USDT")).thenReturn(11L);
        MarkPriceService service = service(coordinationService, kafkaTemplate);
        ObjectMapper objectMapper = new ObjectMapper();
        Instant now = Instant.now();

        service.onBookTicker(objectMapper.writeValueAsString(
                new PerpBookTickerEvent("BTC-USDT", new BigDecimal("100.00"), new BigDecimal("100.10"), 1, now)));
        service.acceptTrade(new PerpTradeEvent("BTC-USDT", "t1", 1, now,
                new BigDecimal("100.05"), BigDecimal.ONE, "BUY"));
        service.acceptIndexPrice(new IndexPriceEvent("BTC-USDT", new BigDecimal("100.00"), 1,
                PriceStatus.HEALTHY, 3, 3, BigDecimal.valueOf(3), now, List.of()));
        verify(kafkaTemplate, never()).send(any(), any(), any());
        service.publishMarkPrices();
        verify(kafkaTemplate).send(eq(properties().priceEventsTopic()), eq("BTC-USDT"),
                any(PricePublishedEvent.class));

        reset(coordinationService, kafkaTemplate);
        service.acceptIndexPrice(new IndexPriceEvent("BTC-USDT", null, 2, PriceStatus.INSUFFICIENT_SOURCES, 3, 1,
                BigDecimal.valueOf(3), now, List.of()));
        service.publishMarkPrices();

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void publishesMarkPriceAfterFreshIndexPriceArrives() throws Exception {
        MarkPriceCoordinationService coordinationService = mock(MarkPriceCoordinationService.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        when(coordinationService.nextSequence("price-mark", "BTC-USDT")).thenReturn(11L);
        when(coordinationService.currentEncoding("BTC-USDT")).thenReturn(encoding());
        MarkPriceProperties properties = properties();
        LatestMarkPriceCache cache = mock(LatestMarkPriceCache.class);
        MarkPriceService service = new MarkPriceService(new ObjectMapper(), properties,
                new MarkPriceCalculator(properties), coordinationService, kafkaTemplate, cache,
                mock(PublicTradeEventMapper.class));
        ObjectMapper objectMapper = new ObjectMapper();
        Instant now = Instant.now();

        service.acceptIndexPrice(new IndexPriceEvent("BTC-USDT", new BigDecimal("100.00"), 2,
                PriceStatus.HEALTHY, 3, 3, BigDecimal.valueOf(3), now, List.of()));
        verify(kafkaTemplate, never()).send(any(), any(), any());
        service.publishMarkPrices();

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(properties.priceEventsTopic()), eq("BTC-USDT"), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(PricePublishedEvent.class);
        PricePublishedEvent publication = (PricePublishedEvent) eventCaptor.getValue();
        assertThat(publication.generatedAt()).isEqualTo(publication.markPrice().calculatedAt());
        assertThat(publication.markPrice().indexInput().components()).isNotNull();
        MarkPriceEvent event = publication.markPrice().result();
        assertThat(event.symbol()).isEqualTo("BTC-USDT");
        assertThat(event.sequence()).isEqualTo(11L);
        assertThat(event.markPrice()).isEqualByComparingTo("100.000000000000000000");
        verify(cache).update(any(MarkPriceEvent.class));
    }

    @Test
    void publishesMarkPriceToProductSpecificTopicsWhenEnabled() throws Exception {
        MarkPriceCoordinationService coordinationService = mock(MarkPriceCoordinationService.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        when(coordinationService.nextSequence("price-mark", "BTC-USDT")).thenReturn(11L);
        when(coordinationService.currentEncoding("BTC-USDT")).thenReturn(encoding());
        MarkPriceProperties properties = properties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        MarkPriceService service = new MarkPriceService(new ObjectMapper(), properties,
                new MarkPriceCalculator(properties), coordinationService, kafkaTemplate,
                mock(LatestMarkPriceCache.class), mock(PublicTradeEventMapper.class));
        Instant now = Instant.now();

        service.acceptIndexPrice(new IndexPriceEvent("BTC-USDT", new BigDecimal("100.00"), 2,
                PriceStatus.HEALTHY, 3, 3, BigDecimal.valueOf(3), now, List.of()));
        verify(kafkaTemplate, never()).send(any(), any(), any());
        service.publishMarkPrices();

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("surprising.linear-delivery.price.events.v1"), eq("BTC-USDT"),
                eventCaptor.capture());
        MarkPriceEvent event = ((PricePublishedEvent) eventCaptor.getValue()).markPrice().result();
        assertThat(event.status()).isEqualTo(PriceStatus.HEALTHY);
    }

    @Test
    void acceptsBookTickerFromCurrentProductTopic() throws Exception {
        MarkPriceCoordinationService coordinationService = mock(MarkPriceCoordinationService.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        MarkPriceProperties properties = properties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        MarkPriceService service = new MarkPriceService(new ObjectMapper(), properties,
                new MarkPriceCalculator(properties), coordinationService, kafkaTemplate,
                mock(LatestMarkPriceCache.class), mock(PublicTradeEventMapper.class));
        String payload = new ObjectMapper().writeValueAsString(new PerpBookTickerEvent("BTC-USDT-260925-70000-C",
                new BigDecimal("100.00"), new BigDecimal("100.10"), 1, Instant.now()));

        service.onBookTicker(new ConsumerRecord<>("surprising.option.book.ticker.v1", 0, 1L,
                "BTC-USDT-260925-70000-C", payload));

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void acceptsCanonicalMatchTradeOnlyWhenKafkaKeyMatchesSymbol() throws Exception {
        MarkPriceCoordinationService coordinationService = mock(MarkPriceCoordinationService.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        PublicTradeEventMapper mapper = mock(PublicTradeEventMapper.class);
        MarkPriceProperties properties = properties();
        MarkPriceService service = new MarkPriceService(new ObjectMapper(), properties,
                new MarkPriceCalculator(properties), coordinationService, kafkaTemplate,
                mock(LatestMarkPriceCache.class), mapper);
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        PublicTradeEvent event = new PublicTradeEvent("match-1", 1L, "BTC-USDT", 8L, OrderSide.BUY,
                10_005L, 200L, now, "trace-1");
        PerpTradeEvent mapped = new PerpTradeEvent("BTC-USDT", "match-1", 1L, now,
                new BigDecimal("100.05"), BigDecimal.ONE, "BUY");
        when(mapper.toPerpTradeEvent(event)).thenReturn(mapped);
        ObjectMapper objectMapper = new ObjectMapper();

        service.onTrade(new ConsumerRecord<>(properties.matchTradesTopic(), 0, 1L, "BTC-USDT",
                objectMapper.writeValueAsString(event)));
        service.onTrade(new ConsumerRecord<>(properties.matchTradesTopic(), 0, 2L, "ETH-USDT",
                objectMapper.writeValueAsString(event)));

        verify(mapper).toPerpTradeEvent(event);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void skipsIndexOnlyUnderlyingWithoutMarkPriceEncoding() throws Exception {
        MarkPriceCoordinationService coordinationService = mock(MarkPriceCoordinationService.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        when(coordinationService.currentEncoding("BTC-USDT"))
                .thenThrow(new IllegalStateException("mark price encoding not found for BTC-USDT"));
        MarkPriceProperties properties = properties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        MarkPriceService service = new MarkPriceService(new ObjectMapper(), properties,
                new MarkPriceCalculator(properties), coordinationService, kafkaTemplate,
                mock(LatestMarkPriceCache.class), mock(PublicTradeEventMapper.class));
        Instant now = Instant.now();

        service.acceptIndexPrice(new IndexPriceEvent("BTC-USDT", new BigDecimal("100.00"), 2,
                PriceStatus.HEALTHY, 3, 3, BigDecimal.valueOf(3), now, List.of()));
        service.publishMarkPrices();

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void rejectsFundingRateFromOtherProductTopicBeforeCaching() throws Exception {
        MarkPriceCoordinationService coordinationService = mock(MarkPriceCoordinationService.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        MarkPriceProperties properties = properties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        MarkPriceService service = new MarkPriceService(new ObjectMapper(), properties,
                new MarkPriceCalculator(properties), coordinationService, kafkaTemplate,
                mock(LatestMarkPriceCache.class), mock(PublicTradeEventMapper.class));
        String payload = new ObjectMapper().writeValueAsString(new PerpFundingRateEvent("BTC-USD",
                BigDecimal.ZERO, Instant.now(), 8, 1, Instant.now()));

        assertThatThrownBy(() -> service.onFundingRate(new ConsumerRecord<>(
                "surprising.linear-perp.funding.rate.v1", 0, 1L, "BTC-USD", payload)))
                .isInstanceOf(MarkPriceService.ProductTopicMismatchException.class)
                .hasMessageContaining("funding rate topic must match current product line")
                .hasMessageContaining("surprising.inverse-perp.funding.rate.v1");

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    private MarkPriceService service(MarkPriceCoordinationService coordinationService,
                                     KafkaTemplate<String, Object> kafkaTemplate) {
        MarkPriceProperties properties = properties();
        when(coordinationService.currentEncoding("BTC-USDT")).thenReturn(encoding());
        return new MarkPriceService(new ObjectMapper(), properties, new MarkPriceCalculator(properties),
                coordinationService, kafkaTemplate, mock(LatestMarkPriceCache.class), mock(PublicTradeEventMapper.class));
    }

    private MarkPriceEncoding encoding() {
        return new MarkPriceEncoding(1L, 100_000_000L, 1_000_000L, 100_000_000L, 1L);
    }

    private MarkPriceProperties properties() {
        MarkPriceProperties properties = new MarkPriceProperties();
        properties.getCoordination().setEnabled(false);
        properties.getCalculation().setMaxInputAge(Duration.ofSeconds(5));
        return properties;
    }
}
