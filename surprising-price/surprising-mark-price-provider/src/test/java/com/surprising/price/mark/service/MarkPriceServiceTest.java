package com.surprising.price.mark.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.MarkPriceAuditEvent;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpFundingRateEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceEncoding;
import com.surprising.price.mark.repository.MarkPriceRepository;
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
        MarkPriceRepository repository = mock(MarkPriceRepository.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        MarkPriceService service = service(repository, kafkaTemplate);
        ObjectMapper objectMapper = new ObjectMapper();
        Instant now = Instant.now();

        service.onBookTicker(objectMapper.writeValueAsString(
                new PerpBookTickerEvent("BTC-USDT", new BigDecimal("100.00"), new BigDecimal("100.10"), 1, now)));
        service.onTrade(objectMapper.writeValueAsString(
                new PerpTradeEvent("BTC-USDT", "t1", 1, now, new BigDecimal("100.05"), BigDecimal.ONE, "BUY")));
        service.onIndexPrice(objectMapper.writeValueAsString(
                new IndexPriceEvent("BTC-USDT", null, 1, PriceStatus.INSUFFICIENT_SOURCES, 3, 1,
                        BigDecimal.valueOf(3), now, List.of())));
        service.publishMarkPrices();

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void doesNotPublishMarkPriceWhenIndexStatusIsNotUsable() throws Exception {
        MarkPriceRepository repository = mock(MarkPriceRepository.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        MarkPriceService service = service(repository, kafkaTemplate);
        ObjectMapper objectMapper = new ObjectMapper();
        Instant now = Instant.now();

        service.onBookTicker(objectMapper.writeValueAsString(
                new PerpBookTickerEvent("BTC-USDT", new BigDecimal("100.00"), new BigDecimal("100.10"), 1, now)));
        service.onTrade(objectMapper.writeValueAsString(
                new PerpTradeEvent("BTC-USDT", "t1", 1, now, new BigDecimal("100.05"), BigDecimal.ONE, "BUY")));
        service.onIndexPrice(objectMapper.writeValueAsString(
                new IndexPriceEvent("BTC-USDT", new BigDecimal("100.00"), 1, PriceStatus.INSUFFICIENT_SOURCES, 3, 1,
                        BigDecimal.valueOf(3), now, List.of())));
        service.publishMarkPrices();

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void insufficientIndexPriceReplacesPreviouslyHealthyIndexPrice() throws Exception {
        MarkPriceRepository repository = mock(MarkPriceRepository.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        when(repository.nextSequence("price-mark", "BTC-USDT")).thenReturn(11L);
        MarkPriceService service = service(repository, kafkaTemplate);
        ObjectMapper objectMapper = new ObjectMapper();
        Instant now = Instant.now();

        service.onBookTicker(objectMapper.writeValueAsString(
                new PerpBookTickerEvent("BTC-USDT", new BigDecimal("100.00"), new BigDecimal("100.10"), 1, now)));
        service.onTrade(objectMapper.writeValueAsString(
                new PerpTradeEvent("BTC-USDT", "t1", 1, now, new BigDecimal("100.05"), BigDecimal.ONE, "BUY")));
        service.onIndexPrice(objectMapper.writeValueAsString(
                new IndexPriceEvent("BTC-USDT", new BigDecimal("100.00"), 1, PriceStatus.HEALTHY, 3, 3,
                        BigDecimal.valueOf(3), now, List.of())));
        verify(kafkaTemplate).send(eq(properties().markPriceTopic()), eq("BTC-USDT"), any(MarkPriceEvent.class));

        reset(repository, kafkaTemplate);
        service.onIndexPrice(objectMapper.writeValueAsString(
                new IndexPriceEvent("BTC-USDT", null, 2, PriceStatus.INSUFFICIENT_SOURCES, 3, 1,
                        BigDecimal.valueOf(3), now, List.of())));
        service.publishMarkPrices();

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void publishesMarkPriceAfterFreshIndexPriceArrives() throws Exception {
        MarkPriceRepository repository = mock(MarkPriceRepository.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        when(repository.nextSequence("price-mark", "BTC-USDT")).thenReturn(11L);
        when(repository.encoding("BTC-USDT")).thenReturn(encoding());
        MarkPriceProperties properties = properties();
        MarkPriceService service = new MarkPriceService(new ObjectMapper(), properties,
                new MarkPriceCalculator(properties), repository, kafkaTemplate);
        ObjectMapper objectMapper = new ObjectMapper();
        Instant now = Instant.now();

        service.onIndexPrice(objectMapper.writeValueAsString(
                new IndexPriceEvent("BTC-USDT", new BigDecimal("100.00"), 2, PriceStatus.HEALTHY, 3, 3,
                        BigDecimal.valueOf(3), now, List.of())));

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(properties.markPriceTopic()), eq("BTC-USDT"), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(MarkPriceEvent.class);
        MarkPriceEvent event = (MarkPriceEvent) eventCaptor.getValue();
        assertThat(event.symbol()).isEqualTo("BTC-USDT");
        assertThat(event.sequence()).isEqualTo(11L);
        assertThat(event.markPrice()).isEqualByComparingTo("100.000000000000000000");
        ArgumentCaptor<Object> auditCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(properties.markPriceAuditTopic()), eq("BTC-USDT"), auditCaptor.capture());
        assertThat(auditCaptor.getValue()).isInstanceOf(MarkPriceAuditEvent.class);
        assertThat(((MarkPriceAuditEvent) auditCaptor.getValue()).result()).isEqualTo(event);
    }

    @Test
    void publishesMarkPriceToProductSpecificTopicsWhenEnabled() throws Exception {
        MarkPriceRepository repository = mock(MarkPriceRepository.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        when(repository.nextSequence("price-mark", "BTC-USDT")).thenReturn(11L);
        when(repository.encoding("BTC-USDT")).thenReturn(encoding());
        MarkPriceProperties properties = properties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        MarkPriceService service = new MarkPriceService(new ObjectMapper(), properties,
                new MarkPriceCalculator(properties), repository, kafkaTemplate);
        Instant now = Instant.now();

        service.onIndexPrice(new ObjectMapper().writeValueAsString(
                new IndexPriceEvent("BTC-USDT", new BigDecimal("100.00"), 2, PriceStatus.HEALTHY, 3, 3,
                        BigDecimal.valueOf(3), now, List.of())));

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("surprising.linear-delivery.mark.price.v1"), eq("BTC-USDT"),
                eventCaptor.capture());
        MarkPriceEvent event = (MarkPriceEvent) eventCaptor.getValue();
        assertThat(event.status()).isEqualTo(PriceStatus.HEALTHY);
        ArgumentCaptor<Object> auditCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("surprising.linear-delivery.mark.price.audit.v1"), eq("BTC-USDT"),
                auditCaptor.capture());
        assertThat(((MarkPriceAuditEvent) auditCaptor.getValue()).result()).isEqualTo(event);
    }

    @Test
    void rejectsIndexPriceFromOtherProductTopicBeforePublishing() throws Exception {
        MarkPriceRepository repository = mock(MarkPriceRepository.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        MarkPriceProperties properties = properties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        MarkPriceService service = new MarkPriceService(new ObjectMapper(), properties,
                new MarkPriceCalculator(properties), repository, kafkaTemplate);
        String payload = new ObjectMapper().writeValueAsString(new IndexPriceEvent("BTC-USDT", BigDecimal.TEN,
                2, PriceStatus.HEALTHY, 3, 3, BigDecimal.valueOf(3), Instant.now(), List.of()));

        assertThatThrownBy(() -> service.onIndexPrice(new ConsumerRecord<>(
                "surprising.inverse-delivery.index.price.v1", 0, 1L, "BTC-USDT", payload)))
                .isInstanceOf(MarkPriceService.ProductTopicMismatchException.class)
                .hasMessageContaining("index price topic must match current product line")
                .hasMessageContaining("surprising.linear-delivery.index.price.v1");

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void acceptsBookTickerFromCurrentProductTopic() throws Exception {
        MarkPriceRepository repository = mock(MarkPriceRepository.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        MarkPriceProperties properties = properties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        MarkPriceService service = new MarkPriceService(new ObjectMapper(), properties,
                new MarkPriceCalculator(properties), repository, kafkaTemplate);
        String payload = new ObjectMapper().writeValueAsString(new PerpBookTickerEvent("BTC-USDT-260925-70000-C",
                new BigDecimal("100.00"), new BigDecimal("100.10"), 1, Instant.now()));

        service.onBookTicker(new ConsumerRecord<>("surprising.option.book.ticker.v1", 0, 1L,
                "BTC-USDT-260925-70000-C", payload));

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void rejectsFundingRateFromOtherProductTopicBeforeCaching() throws Exception {
        MarkPriceRepository repository = mock(MarkPriceRepository.class);
        KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
        MarkPriceProperties properties = properties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);
        MarkPriceService service = new MarkPriceService(new ObjectMapper(), properties,
                new MarkPriceCalculator(properties), repository, kafkaTemplate);
        String payload = new ObjectMapper().writeValueAsString(new PerpFundingRateEvent("BTC-USD",
                BigDecimal.ZERO, Instant.now(), 8, 1, Instant.now()));

        assertThatThrownBy(() -> service.onFundingRate(new ConsumerRecord<>(
                "surprising.linear-perp.funding.rate.v1", 0, 1L, "BTC-USD", payload)))
                .isInstanceOf(MarkPriceService.ProductTopicMismatchException.class)
                .hasMessageContaining("funding rate topic must match current product line")
                .hasMessageContaining("surprising.inverse-perp.funding.rate.v1");

        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    private MarkPriceService service(MarkPriceRepository repository, KafkaTemplate<String, Object> kafkaTemplate) {
        MarkPriceProperties properties = properties();
        when(repository.encoding("BTC-USDT")).thenReturn(encoding());
        return new MarkPriceService(new ObjectMapper(), properties, new MarkPriceCalculator(properties), repository,
                kafkaTemplate);
    }

    private MarkPriceEncoding encoding() {
        return new MarkPriceEncoding(1L, 100_000_000L, 1_000_000L);
    }

    private MarkPriceProperties properties() {
        MarkPriceProperties properties = new MarkPriceProperties();
        properties.getCoordination().setEnabled(false);
        properties.getCalculation().setPublishDelayMs(0);
        properties.getCalculation().setMaxInputAge(Duration.ofSeconds(5));
        return properties;
    }
}
