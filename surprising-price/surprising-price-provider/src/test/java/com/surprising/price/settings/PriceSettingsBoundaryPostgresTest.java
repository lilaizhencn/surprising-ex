package com.surprising.price.settings;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.settings.model.PriceBusinessSettings;
import com.surprising.price.settings.repository.PriceBusinessSettingsRepository;
import com.surprising.price.settings.service.PriceBusinessSettingsService;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@EnabledIfEnvironmentVariable(named="PRICE_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sixline_qa")
class PriceSettingsBoundaryPostgresTest {
    @Configuration @EnableTransactionManagement static class Transactions { }
    private final JsonMapper json=JsonMapper.builder().findAndAddModules().build();
    private JdbcTemplate jdbc() { return new JdbcTemplate(new DriverManagerDataSource(System.getenv("PRICE_TEST_JDBC_URL"),"sixline_qa","qa")); }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void malformedPersistedRulesNeverInstallOrPublishAndOtherLinesStayUntouched(ProductLine line) {
        var jdbc=jdbc();
        jdbc.update("DELETE FROM price_business_settings WHERE product_line=?",line.name());
        var index=new IndexPriceProperties(); index.getKafka().setProductLine(line);index.getCoordination().setNodeId("index-node");
        var mark=new MarkPriceProperties();mark.getKafka().setProductLine(line);mark.getCoordination().setNodeId("mark-node");
        var events=mock(ApplicationEventPublisher.class);
        var service=new PriceBusinessSettingsService(new PriceBusinessSettingsRepository(jdbc,json),json,index,mark,events);
        service.initialize();
        var saved=service.current();clearInvocations(events);
        var others=jdbc.queryForList("SELECT * FROM price_business_settings WHERE product_line<>? ORDER BY product_line",line.name());
        String valid=json.writeValueAsString(saved.settings());
        for (String bad : List.of(valid.replace("\"indexPollDelayMs\":1000","\"indexPollDelayMs\":\"100\""),
                valid.replace("\"indexPollDelayMs\":1000","\"indexPollDelayMs\":50.5"),
                valid.replace("\"indexScale\":18,",""),valid.replace("\"websocketEnabled\":true,",""),
                valid.replace("\"websocketEnabled\":true","\"websocketEnabled\":\"false\""),
                valid.replace("\"indexScale\":18","\"indexScale\":null"),
                valid.replace("\"indexScale\":18","\"indexScale\":-1"),
                valid.replace("\"fiatEnabled\":false","\"fiatEnabled\":true"),
                valid.replace("{","{\"unknown\":1,"))) {
            assertThat(bad).isNotEqualTo(valid);
            jdbc.update("UPDATE price_business_settings SET settings=?::jsonb,version=version+1 WHERE product_line=?",bad,line.name());
            assertThatThrownBy(service::reload).isInstanceOf(tools.jackson.core.JacksonException.class);
            assertThat(service.current()).isEqualTo(saved);
            assertThat(index.getCalculation().getPollDelayMs()).isEqualTo(1000);
            assertThat(index.getWebSocket().isEnabled()).isTrue();
            verifyNoInteractions(events);
        }
        jdbc.update("UPDATE price_business_settings SET settings=?::jsonb,version=version+1 WHERE product_line=?",valid,line.name());
        service.reload();
        verify(events).publishEvent(service.current());
        assertThat(index.getCoordination().getNodeId()).isEqualTo("index-node");
        assertThat(mark.getCoordination().getNodeId()).isEqualTo("mark-node");
        assertThat(jdbc.queryForList("SELECT * FROM price_business_settings WHERE product_line<>? ORDER BY product_line",line.name())).isEqualTo(others);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void callerRollbackCannotUndoTheInstalledDatabaseVersion(ProductLine line) {
        var jdbc=jdbc();jdbc.update("DELETE FROM price_business_settings WHERE product_line=?",line.name());
        var index=new IndexPriceProperties();index.getKafka().setProductLine(line);
        var mark=new MarkPriceProperties();mark.getKafka().setProductLine(line);
        var repository=new PriceBusinessSettingsRepository(jdbc,json);
        var manager=new DataSourceTransactionManager(jdbc.getDataSource());
        try (var context=new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);context.registerBean(DataSourceTransactionManager.class,()->manager);
            context.registerBean(PriceBusinessSettingsService.class,()->new PriceBusinessSettingsService(repository,json,index,mark,event -> {}));
            context.refresh();var service=context.getBean(PriceBusinessSettingsService.class);
            long version=service.current().version();
            var changed=(ObjectNode)json.valueToTree(service.current().settings());changed.put("indexPollDelayMs",50);
            var next=json.treeToValue(changed,PriceBusinessSettings.class);
            new TransactionTemplate(manager).executeWithoutResult(status->{
                service.save(next,version,"42","independent commit");status.setRollbackOnly();
            });
            assertThat(repository.find(line)).isEqualTo(service.current());
            assertThat(service.current().version()).isEqualTo(version+1);
            assertThat(index.getCalculation().getPollDelayMs()).isEqualTo(50);
        }
    }
}
