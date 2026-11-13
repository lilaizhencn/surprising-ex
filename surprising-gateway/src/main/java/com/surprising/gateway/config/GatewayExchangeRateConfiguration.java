package com.surprising.gateway.config;

import com.surprising.price.index.client.ExternalSpotPriceClient;
import com.surprising.price.index.client.FiatRateClient;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.controller.ExchangeRateController;
import com.surprising.price.index.repository.ExchangeRateRepository;
import com.surprising.price.index.service.ExchangeRateService;
import com.surprising.price.index.service.ExchangeRateSnapshotCache;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

/** 汇率与外部现货行情客户端不区分产品线，留在根容器供资产估值与各产品线共享。 */
@Configuration(proxyBeanMethods = false)
@Import({ExchangeRateRepository.class, ExchangeRateSnapshotCache.class, ExchangeRateController.class})
public class GatewayExchangeRateConfiguration {

    private static IndexPriceProperties bindHttpAndFiat(Environment environment) {
        var properties = new IndexPriceProperties();
        var binder = Binder.get(environment);
        binder.bind("surprising.price.index.http", Bindable.ofInstance(properties.getHttp()));
        binder.bind("surprising.price.index.fiat", Bindable.ofInstance(properties.getFiat()));
        return properties;
    }

    @Bean
    public FiatRateClient fiatRateClient(Environment environment, ObjectMapper objectMapper) {
        return new FiatRateClient(bindHttpAndFiat(environment), objectMapper);
    }

    @Bean
    public ExternalSpotPriceClient externalSpotPriceClient(Environment environment, ObjectMapper objectMapper) {
        return new ExternalSpotPriceClient(bindHttpAndFiat(environment), objectMapper);
    }

    @Bean
    public ExchangeRateService exchangeRateService(Environment environment,
                                                   ExchangeRateRepository exchangeRateRepository,
                                                   ExternalSpotPriceClient externalSpotPriceClient,
                                                   FiatRateClient fiatRateClient,
                                                   ExchangeRateSnapshotCache snapshotCache) {
        return new ExchangeRateService(bindHttpAndFiat(environment), exchangeRateRepository,
                externalSpotPriceClient, fiatRateClient, snapshotCache);
    }
}
