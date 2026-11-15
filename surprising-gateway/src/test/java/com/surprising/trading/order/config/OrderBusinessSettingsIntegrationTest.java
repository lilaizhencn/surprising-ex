package com.surprising.trading.order.config;

import static org.assertj.core.api.Assertions.*;
import com.surprising.aeron.protocol.CoreOrderProtection;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.model.OrderBusinessSettings;
import com.surprising.trading.order.repository.OrderBusinessSettingsRepository;
import com.surprising.trading.order.service.OrderBusinessSettingsService;
import com.surprising.trading.order.controller.OrderBusinessSettingsController;
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
        var first=new OrderBusinessSettingsService(new OrderBusinessSettingsRepository(jdbc,json),properties);first.initialize();
        assertThat(properties.getRisk().getMarketMaxSlippagePpm()).isEqualTo(10000);
        assertThat(properties.getAlgo().isEnabled()).isTrue();
        var secondProps=infra.tradingOrderProperties(env);
        var second=new OrderBusinessSettingsService(new OrderBusinessSettingsRepository(jdbc,json),secondProps);second.initialize();
        var before=first.current(line);
        var settings=new OrderBusinessSettings(new CoreOrderProtection(20000,8000,true,30000,4000),
                new OrderBusinessSettings.Algo(false,50,100,2,100,10,300,5000));
        first.save(line,settings,before.configurationVersion(),"42","configure orders");
        second.reload();
        assertThat(second.current(line)).isEqualTo(first.current(line));
        assertThat(secondProps.getRisk().protection()).isEqualTo(settings.risk());
        assertThat(secondProps.getAlgo().getScanDelayMs()).isEqualTo(100);
        assertThat(secondProps.getAlgo().isEnabled()).isFalse();
        var controller = new OrderBusinessSettingsController(second,json);
        String stale=json.writeValueAsString(new OrderBusinessSettingsController.Update(settings,before.configurationVersion(),"stale"));
        assertThatThrownBy(()->controller.save(line,json.readTree(stale),"42"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        var saved=first.current(line);
        jdbc.execute("ALTER TABLE order_business_settings ADD CONSTRAINT mvc_reject_reason CHECK(reason <> 'database fault') NOT VALID");
        try {
            var rejected=new OrderBusinessSettings(CoreOrderProtection.initial(),OrderBusinessSettings.initial().algo());
            assertThatThrownBy(()->first.save(line,rejected,saved.configurationVersion(),"42","database fault"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(first.current(line)).isEqualTo(saved);
            assertThat(properties.getRisk().protection()).isEqualTo(settings.risk());
            assertThat(properties.getAlgo().isEnabled()).isFalse();
            second.reload();
            assertThat(second.current(line)).isEqualTo(saved);
        } finally { jdbc.execute("ALTER TABLE order_business_settings DROP CONSTRAINT mvc_reject_reason"); }
        assertThatThrownBy(()->new CoreOrderProtection(1000000,5000,false,50000,5000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OrderBusinessSettings.Algo(true,100,25,20,10,5,100,30000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->first.current(line==ProductLine.SPOT?ProductLine.OPTION:ProductLine.SPOT)).isInstanceOf(IllegalArgumentException.class);
        assertThat(first.current(line).configurationVersion()).isEqualTo(before.configurationVersion()+1);
    }
    @org.springframework.context.annotation.Configuration
    @org.springframework.transaction.annotation.EnableTransactionManagement
    static class Transactions { }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void savedDatabaseVersionAndInstalledRulesSurviveCallerRollback(ProductLine line) {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(System.getenv("ORDER_SETTINGS_TEST_JDBC_URL"),"sixline_qa","qa"));
        jdbc.update("DELETE FROM order_business_settings WHERE product_line=?",line.name());
        var properties=new TradingOrderProperties();
        properties.getKafka().setProductLine(line);
        var json=JsonMapper.builder().findAndAddModules().build();
        var repository=new OrderBusinessSettingsRepository(jdbc,json);
        try (var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            context.registerBean(OrderBusinessSettingsRepository.class,()->repository);
            context.registerBean(TradingOrderProperties.class,()->properties);
            var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
            context.registerBean(org.springframework.jdbc.datasource.DataSourceTransactionManager.class,()->manager);
            context.register(OrderBusinessSettingsService.class);
            context.refresh();
            var service=context.getBean(OrderBusinessSettingsService.class);
            long version=service.current(line).configurationVersion();
            var modified=new OrderBusinessSettings(new CoreOrderProtection(20000,8000,true,30000,4000),OrderBusinessSettings.initial().algo());
            new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->{
                service.save(line,modified,version,"42","committed configuration");
                status.setRollbackOnly();
            });
            assertThat(repository.find(line)).isEqualTo(service.current(line));
            assertThat(service.current(line).configurationVersion()).isEqualTo(version+1);
            assertThat(properties.getRisk().protection()).isEqualTo(modified.risk());
        }
    }

}
