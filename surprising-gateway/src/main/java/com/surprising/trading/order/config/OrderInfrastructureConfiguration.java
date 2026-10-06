package com.surprising.trading.order.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
public class OrderInfrastructureConfiguration {
    @Bean public TradingOrderProperties tradingOrderProperties(Environment env) {
        var p=new TradingOrderProperties();var binder=Binder.get(env);
        binder.bind("surprising.trading.order.kafka",Bindable.ofInstance(p.getKafka()));
        binder.bind("surprising.trading.order.aeron",Bindable.ofInstance(p.getAeron()));
        binder.bind("surprising.trading.order.event-publish",Bindable.ofInstance(p.getEventPublish()));
        if(p.getKafka().getProductLine()==null)throw new IllegalArgumentException("order product line required");
        return p;
    }
}
