package com.surprising.price.settings;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.mark.config.MarkPriceProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 环境只绑定连接和运行设施；价格业务参数由数据库安装。 */
@Configuration(proxyBeanMethods = false)
public class PriceInfrastructureConfiguration {
    @Bean public IndexPriceProperties indexPriceProperties(Environment environment) {
        var value = new IndexPriceProperties(); var binder = Binder.get(environment);
        binder.bind("surprising.price.index.kafka", Bindable.ofInstance(value.getKafka()));
        binder.bind("surprising.price.index.http", Bindable.ofInstance(value.getHttp()));
        binder.bind("surprising.price.index.audit", Bindable.ofInstance(value.getAudit()));
        value.getCoordination().setNodeId(environment.getProperty("PRICE_INDEX_NODE_ID"));
        return value;
    }
    @Bean public MarkPriceProperties markPriceProperties(Environment environment) {
        var value = new MarkPriceProperties(); var binder = Binder.get(environment);
        binder.bind("surprising.price.mark.kafka", Bindable.ofInstance(value.getKafka()));
        binder.bind("surprising.price.mark.aeron", Bindable.ofInstance(value.getAeron()));
        binder.bind("surprising.price.mark.audit", Bindable.ofInstance(value.getAudit()));
        value.getCoordination().setNodeId(environment.getProperty("PRICE_MARK_NODE_ID"));
        return value;
    }
}
