package com.surprising.price.mark.consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.exception.ProductTopicMismatchException;
import com.surprising.price.mark.service.MarkPriceService;
import com.surprising.price.mark.service.PublicTradeEventMapper;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PublicTradeEvent;
import java.math.BigDecimal;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

class MarkPriceConsumerTest {
    private final ObjectMapper json=new ObjectMapper();

    @ParameterizedTest @EnumSource(ProductLine.class)
    void acceptsOwnBookAndRejectsEveryOtherProductBeforeBusiness(ProductLine product) {
        var properties=properties(product);
        var service=mock(MarkPriceService.class);
        var mapper=mock(PublicTradeEventMapper.class);
        var consumer=new MarkPriceConsumer(json,properties,mapper,service);
        var event=new PerpBookTickerEvent("1",new BigDecimal("100.00"),new BigDecimal("100.10"),Long.MAX_VALUE,Instant.now());
        String payload=json.writeValueAsString(event);
        consumer.onBookTicker(new ConsumerRecord<>(properties.bookTickerTopic(),0,1,"1",payload));
        verify(service).acceptBookTicker(event);
        clearInvocations(service);
        for(var other:ProductLine.values())if(other!=product)
            assertThatThrownBy(()->consumer.onBookTicker(new ConsumerRecord<>(properties(other).bookTickerTopic(),0,1,"1",payload)))
                    .isInstanceOf(ProductTopicMismatchException.class);
        verifyNoInteractions(service,mapper);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void tradeKeyAndProductAreCheckedBeforeMappingOrState(ProductLine product) {
        var properties=properties(product);
        var service=mock(MarkPriceService.class);
        var mapper=mock(PublicTradeEventMapper.class);
        var consumer=new MarkPriceConsumer(json,properties,mapper,service);
        var event=new PublicTradeEvent("trade-1",Long.MAX_VALUE,"1",OrderSide.BUY,100,1,Instant.now());
        String payload=json.writeValueAsString(event);
        consumer.onTrade(new ConsumerRecord<>(properties.matchTradesTopic(),0,1,"other",payload));
        for(var other:ProductLine.values())if(other!=product)
            assertThatThrownBy(()->consumer.onTrade(new ConsumerRecord<>(properties(other).matchTradesTopic(),0,1,"1",payload)))
                    .isInstanceOf(ProductTopicMismatchException.class);
        verifyNoInteractions(mapper,service);
    }

    @Test void malformedMessagesNeverReachBusiness() {
        var properties=properties(ProductLine.LINEAR_PERPETUAL);
        var service=mock(MarkPriceService.class);
        var mapper=mock(PublicTradeEventMapper.class);
        var consumer=new MarkPriceConsumer(json,properties,mapper,service);
        consumer.onBookTicker(new ConsumerRecord<>(properties.bookTickerTopic(),0,1,"1","broken"));
        consumer.onTrade(new ConsumerRecord<>(properties.matchTradesTopic(),0,1,"1","broken"));
        consumer.onFundingRate(new ConsumerRecord<>(properties.fundingRateTopic(),0,1,"1","broken"));
        verifyNoInteractions(mapper,service);
    }

    @Test void fundingMessageKeepsExactRateSequenceAndTimeAtBusinessEntry() {
        var properties=properties(ProductLine.LINEAR_PERPETUAL);
        var service=mock(MarkPriceService.class);
        var mapper=mock(PublicTradeEventMapper.class);
        var consumer=new MarkPriceConsumer(json,properties,mapper,service);
        var now=Instant.now();
        var event=new com.surprising.price.api.model.PerpFundingRateEvent("1",new BigDecimal("0.000123456789012345"),now.plusSeconds(28800),8,Long.MAX_VALUE,now);
        consumer.onFundingRate(new ConsumerRecord<>(properties.fundingRateTopic(),0,1,"1",json.writeValueAsString(event)));
        verify(service).acceptFundingRate(event);
        verifyNoInteractions(mapper);
    }

    private MarkPriceProperties properties(ProductLine product) {
        var properties=new MarkPriceProperties();properties.getKafka().setProductLine(product);return properties;
    }
}
