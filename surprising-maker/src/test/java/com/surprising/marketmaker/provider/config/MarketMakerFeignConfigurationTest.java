package com.surprising.marketmaker.provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import com.surprising.product.api.ProductLine;
import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MarketMakerFeignConfigurationTest {
    @AfterEach void clearProductLine() { MarketMakerProductLineContext.clear(); }

    @Test void feignInterceptorForwardsCurrentProductLine() {
        MarketMakerProductLineContext.set(ProductLine.LINEAR_DELIVERY);
        RequestTemplate template = new RequestTemplate();
        new MarketMakerFeignConfiguration().marketMakerProductLineRequestInterceptor().apply(template);
        assertThat(template.headers()).containsEntry("X-Product-Line", java.util.List.of("LINEAR_DELIVERY"));
    }

    @Test void feignOptionsBoundTheInternalRpcCall() {
        Request.Options options = new MarketMakerFeignConfiguration().marketMakerRequestOptions();
        assertThat(options.connectTimeoutMillis()).isEqualTo(1000);
        assertThat(options.readTimeoutMillis()).isEqualTo(5000);
        assertThat(options.isFollowRedirects()).isTrue();
    }
}
