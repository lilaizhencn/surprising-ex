package com.surprising.derivatives.lifecycle;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.derivatives.lifecycle.model.LifecycleBusinessSettings;
import com.surprising.derivatives.lifecycle.repository.LifecycleBusinessSettingsRepository;
import com.surprising.derivatives.lifecycle.service.LifecycleBusinessSettingsService;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="LIFECYCLE_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sixline_qa")
class LifecycleSettingsBoundaryPostgresTest {
    @Configuration @EnableTransactionManagement static class Transactions { }
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    private record Instance(LifecycleBusinessSettingsService service,LifecycleBusinessSettingsRepository repository,
                            LiquidationProperties liquidation,FundingProperties funding) { }
    private JdbcTemplate jdbc() { return new JdbcTemplate(new DriverManagerDataSource(System.getenv("LIFECYCLE_TEST_JDBC_URL"),"sixline_qa","qa")); }
    private Instance instance(JdbcTemplate jdbc,ProductLine line,ApplicationEventPublisher events) {
        var risk=new RiskProperties();risk.setProductLine(line);
        var funding=new FundingProperties();funding.getCoordination().setNodeId("funding-node");
        var beans=new StaticListableBeanFactory(line.isFundingProduct()?Map.of("funding",funding):Map.of());
        var liquidation=new LiquidationProperties();var repository=new LifecycleBusinessSettingsRepository(jdbc,json);
        var service=new LifecycleBusinessSettingsService(repository,risk,beans.getBeanProvider(FundingProperties.class),
                liquidation,new InsuranceProperties(),new AdlProperties(),events);
        return new Instance(service,repository,liquidation,funding);
    }

    @ParameterizedTest @EnumSource(value=ProductLine.class,names="SPOT",mode=EnumSource.Mode.EXCLUDE)
    void malformedPersistedRulesNeverInstallOrPublishAndOtherProductsStayUntouched(ProductLine line) {
        var jdbc=jdbc();jdbc.update("DELETE FROM lifecycle_business_settings WHERE product_line=?",line.name());
        var events=mock(ApplicationEventPublisher.class);var h=instance(jdbc,line,events);h.service.initialize();
        var saved=h.service.current();clearInvocations(events);
        var others=jdbc.queryForList("SELECT * FROM lifecycle_business_settings WHERE product_line<>? ORDER BY product_line",line.name());
        String valid=json.writeValueAsString(saved.settings());
        for (String bad : List.of(valid.replace("\"publishDelayMs\":1000","\"publishDelayMs\":\"50\""),
                valid.replace("\"publishDelayMs\":1000","\"publishDelayMs\":50.5"),
                valid.replace("\"feeRatePpm\":3000,",""),valid.replace("\"calculationEnabled\":true,",""),
                valid.replace("\"calculationEnabled\":true","\"calculationEnabled\":\"false\""),
                valid.replace("\"feeRatePpm\":3000","\"feeRatePpm\":null"),
                valid.replace("\"candidateMultiplier\":5","\"candidateMultiplier\":-1"),
                valid.replace("\"funding\":{","\"funding\":{\"unknown\":1,"))) {
            assertThat(bad).isNotEqualTo(valid);
            jdbc.update("UPDATE lifecycle_business_settings SET settings=?::jsonb,version=version+1 WHERE product_line=?",bad,line.name());
            assertThatThrownBy(h.service::reload).isInstanceOf(tools.jackson.core.JacksonException.class);
            assertThat(h.service.current()).isEqualTo(saved);
            assertThat(h.liquidation.getExecution().isEnabled()).isTrue();
            verifyNoInteractions(events);
        }
        jdbc.update("UPDATE lifecycle_business_settings SET settings=?::jsonb,version=version+1 WHERE product_line=?",valid,line.name());
        h.service.reload();verify(events).publishEvent(h.service.current());
        assertThat(h.funding.getCoordination().getNodeId()).isEqualTo("funding-node");
        assertThat(jdbc.queryForList("SELECT * FROM lifecycle_business_settings WHERE product_line<>? ORDER BY product_line",line.name())).isEqualTo(others);
    }

    @ParameterizedTest @EnumSource(value=ProductLine.class,names="SPOT",mode=EnumSource.Mode.EXCLUDE)
    void callerRollbackKeepsInstalledAndCommittedVersionsConsistent(ProductLine line) {
        var jdbc=jdbc();jdbc.update("DELETE FROM lifecycle_business_settings WHERE product_line=?",line.name());
        var h=instance(jdbc,line,event -> {});var manager=new DataSourceTransactionManager(jdbc.getDataSource());
        try (var context=new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);context.registerBean(DataSourceTransactionManager.class,()->manager);
            context.registerBean(LifecycleBusinessSettingsService.class,()->h.service);context.refresh();
            var service=context.getBean(LifecycleBusinessSettingsService.class);var saved=service.current();var old=saved.settings();
            var changed=new LifecycleBusinessSettings(old.funding(),new LifecycleBusinessSettings.Liquidation(false,2500,100,32,2,4096),old.insurance(),old.adl());
            new TransactionTemplate(manager).executeWithoutResult(status->{
                service.save(changed,saved.version(),"42","independent commit");status.setRollbackOnly();
            });
            assertThat(h.repository.find(line)).isEqualTo(service.current());
            assertThat(service.current().version()).isEqualTo(saved.version()+1);
            assertThat(h.liquidation.getExecution().isEnabled()).isFalse();
        }
    }
}
