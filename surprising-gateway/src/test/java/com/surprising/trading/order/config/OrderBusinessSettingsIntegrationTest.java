package com.surprising.trading.order.config;

import static org.assertj.core.api.Assertions.*;
import com.surprising.aeron.protocol.CoreOrderProtection;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="ORDER_SETTINGS_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sixline_qa")
class OrderBusinessSettingsIntegrationTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void databaseIsAuthorityAndUpdatesReloadWithoutAllowingStaleOrMalformedWrites(ProductLine line) {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("ORDER_SETTINGS_TEST_JDBC_URL"),"sixline_qa","qa"));
        jdbc.update("DELETE FROM order_business_settings WHERE product_line=?",line.name());
        var env=new MockEnvironment().withProperty("surprising.trading.order.kafka.product-line",line.name())
                .withProperty("surprising.trading.order.risk.market-max-slippage-ppm","900000")
                .withProperty("surprising.trading.order.algo.enabled","false")
                .withProperty("surprising.trading.order.aeron.client-connections","2");
        var infra=new OrderInfrastructureConfiguration();var properties=infra.tradingOrderProperties(env);
        assertThat(properties.getAeron().getClientConnections()).isEqualTo(2);
        var json=JsonMapper.builder().findAndAddModules().build();
        var first=new OrderBusinessSettingsService(jdbc,json,properties);first.initialize();
        assertThat(properties.getRisk().getMarketMaxSlippagePpm()).isEqualTo(10000);
        assertThat(properties.getAlgo().isEnabled()).isTrue();
        var secondProps=infra.tradingOrderProperties(env);
        var second=new OrderBusinessSettingsService(jdbc,json,secondProps);second.initialize();
        var before=first.current(line);
        var settings=new OrderBusinessSettings(new CoreOrderProtection(20000,8000,true,30000,4000),
                new OrderBusinessSettings.Algo(false,50,100,2,100,10,300,5000));
        first.save(line,new OrderBusinessSettingsService.Update(settings,before.version(),"configure orders"),"42");
        second.reload();
        assertThat(second.current(line)).isEqualTo(first.current(line));
        assertThat(secondProps.getRisk().protection()).isEqualTo(settings.risk());
        assertThat(secondProps.getAlgo().getScanDelayMs()).isEqualTo(100);
        assertThat(secondProps.getAlgo().isEnabled()).isFalse();
        assertThatThrownBy(()->second.save(line,new OrderBusinessSettingsService.Update(settings,before.version(),"stale"),"42"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        String valid=json.writeValueAsString(new OrderBusinessSettingsService.Update(settings,first.current(line).version(),"valid"));
        assertThat(first.parseUpdate(json.readTree(valid)).settings()).isEqualTo(settings);
        for(String bad:java.util.List.of(valid.replace("20000","2.5"),valid.replace("\"enabled\":false","\"enabled\":\"false\""),
                valid.replace("\"marketMaxSlippagePpm\":20000,",""),valid.replace("\"risk\":{","\"risk\":{\"unknown\":1,")))
            assertThatThrownBy(()->first.parseUpdate(json.readTree(bad))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new CoreOrderProtection(1000000,5000,false,50000,5000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OrderBusinessSettings.Algo(true,100,25,20,10,5,100,30000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->first.current(line==ProductLine.SPOT?ProductLine.OPTION:ProductLine.SPOT)).isInstanceOf(IllegalArgumentException.class);
        assertThat(first.current(line).version()).isEqualTo(before.version()+1);
    }
}
