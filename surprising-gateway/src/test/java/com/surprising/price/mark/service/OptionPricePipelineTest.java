package com.surprising.price.mark.service;

import static org.assertj.core.api.Assertions.*;
import com.surprising.price.index.client.ExternalSpotPriceClient;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.service.IndexPriceCalculator;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceEncoding;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OptionPricePipelineTest {
    @Test
    void transportsDistinctPremiumIndexAndSameExpiryForwardToCore() {
        var indexProperties = new IndexPriceProperties(); indexProperties.getKafka().setProductLine(ProductLine.OPTION);
        var markProperties = new MarkPriceProperties(); markProperties.getKafka().setProductLine(ProductLine.OPTION);
        Instant now = Instant.parse("2026-10-05T10:00:00Z"), expiry = now.plusSeconds(86400);
        var source = new IndexPriceProperties.SourceConfig();
        source.setName("option-source"); source.setSourceSymbol("BTC-CALL"); source.setParser("OPTION_RISK_TICKER");
        source.setQuoteCurrency("USDT"); source.setTargetQuoteCurrency("USDT"); source.setWeight(BigDecimal.ONE);
        String payload = "{\"symbol\":\"BTC-CALL\",\"markPrice\":\"1000\",\"indexPrice\":\"50000\",\"sameExpiryForwardPrice\":\"50500\",\"expiryTime\":\"" + expiry + "\",\"timestamp\":" + now.toEpochMilli() + "}";
        var client = new ExternalSpotPriceClient(indexProperties, new ObjectMapper());
        try {
            var quote = client.parsePayload(source, payload, now, 0L);
            assertThat(quote.healthy()).isTrue();
            var index = new IndexPriceCalculator(indexProperties).calculate("1", 1, 1, List.of(quote), now);
            var encoding = new MarkPriceEncoding(1, 100_000_000, 10_000_000, 100_000_000, 100_000, expiry);
            var calculator = new MarkPriceCalculator(markProperties);
            var mark = calculator.calculate("1", 1, index, null, null, null, null, encoding, now);
            assertThat(mark.markPrice()).isEqualByComparingTo("1000");
            assertThat(mark.indexPrice()).isEqualByComparingTo("50000");
            assertThat(mark.sameExpiryForwardPrice()).isEqualByComparingTo("50500");
            assertThat(mark.nextFundingTime()).isNull();
            var command = MarkPriceCorePublisher.toCommand(mark);
            assertThat(command.markPriceTicks()).isEqualTo(10_000);
            assertThat(command.indexPriceTicks()).isEqualTo(500_000);
            assertThat(command.forwardPriceTicks()).isEqualTo(505_000);
            var wrongExpiry = new MarkPriceEncoding(1, 100_000_000, 10_000_000, 100_000_000, 100_000, expiry.plusSeconds(1));
            assertThatThrownBy(() -> calculator.calculate("1", 2, index, null, null, null, null, wrongExpiry, now))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expiry");
            assertThat(client.parsePayload(source, payload.replace("50500", "0"), now, 0L).healthy()).isFalse();
            assertThat(client.parsePayload(source, payload.replace("BTC-CALL", "BTC-PUT"), now, 0L).healthy()).isFalse();
            assertThat(client.parsePayload(source, payload.replace(Long.toString(now.toEpochMilli()), Long.toString(now.plusSeconds(1).toEpochMilli())), now, 0L).healthy()).isFalse();
            var plainSpot = new com.surprising.price.index.model.SourceQuote(quote.source(), quote.sourceSymbol(), quote.price(),
                    null, null, quote.configuredWeight(), quote.status(), null, now, now, 0L, quote.transport());
            assertThat(new IndexPriceCalculator(indexProperties).calculate("1", 3, 1, List.of(plainSpot), now).indexPrice()).isNull();
            var stale = new IndexPriceCalculator(indexProperties).calculate("1", 2, 1, List.of(quote), now.plusSeconds(60));
            assertThat(stale.indexPrice()).isNull();
        } finally { client.close(); }
    }
}
