package com.surprising.marketmaker.provider.repository;

import static org.assertj.core.api.Assertions.*;
import com.surprising.marketmaker.provider.config.MarketMakerBusinessSettings;
import com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class MarketMakerSettingsPostgresTest {
    private JdbcTemplate jdbc;
    private final String schema = "maker_settings_it_" + java.util.UUID.randomUUID().toString().replace("-", "");
    @BeforeEach void prepare() {
        var url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        var user = System.getenv("INSTRUMENT_TEST_DB_USER");
        var password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        admin.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?")
                + "currentSchema=" + schema, user, password));
        for (String table : List.of("market_maker_business_settings", "market_maker_strategies", "market_maker_strategy_overrides"))
            jdbc.execute("CREATE TABLE " + table + " (LIKE public." + table + " INCLUDING ALL)");
    }
    @AfterEach void clean() { if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }

    @Test void settingsSurviveRestartAndRejectStaleSaveWithoutChangingCurrentValues() {
        var json = JsonMapper.builder().findAndAddModules().build();
        var store = new MarketMakerBusinessSettingsStore(jdbc, json);
        var line = ProductLine.LINEAR_PERPETUAL;
        store.initializeDisabled(line);
        var initial = store.load(line);
        assertThat(initial.settings().engine().isEnabled()).isFalse();
        initial.settings().quoting().setOrderLevels(9);
        assertThat(store.save(line, initial.settings(), 1, "1", "test change").version()).isEqualTo(2);
        var restarted = new MarketMakerBusinessSettingsStore(jdbc, json);
        restarted.initializeDisabled(line);
        assertThat(restarted.load(line).settings().quoting().getOrderLevels()).isEqualTo(9);
        assertThatThrownBy(() -> restarted.save(line, MarketMakerBusinessSettings.initialDisabled(), 1, "2", "stale change"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(restarted.load(line).version()).isEqualTo(2);
        assertThat(restarted.load(line).settings().quoting().getOrderLevels()).isEqualTo(9);
    }

    @Test void strategyCreationAndEditingUseVersionAndProductLine() {
        var store = new JdbcMarketMakerStrategyOverrideStore(jdbc);
        var request = definition(ProductLine.LINEAR_PERPETUAL, 0, 10);
        var saved = store.saveDefinition(request, "1", "create");
        assertThat(saved.version()).isEqualTo(1);
        assertThatThrownBy(() -> store.saveDefinition(request, "2", "duplicate"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(store.saveDefinition(definition(ProductLine.LINEAR_PERPETUAL, 1, 12), "1", "edit").version()).isEqualTo(2);
        assertThatThrownBy(() -> store.saveDefinition(definition(ProductLine.LINEAR_PERPETUAL, 1, 99), "2", "stale"))
                .isInstanceOf(ResponseStatusException.class);
        store.saveDefinition(definition(ProductLine.INVERSE_PERPETUAL, 0, 8), "1", "other line");
        assertThat(store.definitions()).hasSize(2).anySatisfy(d -> {
            assertThat(d.productLine()).isEqualTo(ProductLine.LINEAR_PERPETUAL);
            assertThat(d.baseQuantitySteps()).isEqualTo(12);
        });
    }

    @Test void quoteCyclesReuseConfigurationWithoutRepeatedDatabaseReads() {
        var observed = org.mockito.Mockito.spy(jdbc);
        var store = new JdbcMarketMakerStrategyOverrideStore(observed);
        store.saveDefinition(definition(ProductLine.LINEAR_PERPETUAL, 0, 10), "1", "create");
        org.mockito.Mockito.clearInvocations(observed);
        for (int i = 0; i < 100; i++) {
            assertThat(store.definitions()).hasSize(1);
            assertThat(store.find(ProductLine.LINEAR_PERPETUAL, "test-maker")).isEmpty();
        }
        org.mockito.Mockito.verify(observed, org.mockito.Mockito.times(2))
                .query(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<?>>any());
    }

    @Test void configurationCacheChangesAfterCommitAndKeepsPreviousValuesAfterRollback() {
        var store = new JdbcMarketMakerStrategyOverrideStore(jdbc);
        store.saveDefinition(definition(ProductLine.LINEAR_PERPETUAL, 0, 10), "1", "create");
        assertThat(store.definitions().getFirst().baseQuantitySteps()).isEqualTo(10);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.executeWithoutResult(status -> {
            store.saveDefinition(definition(ProductLine.LINEAR_PERPETUAL, 1, 12), "1", "edit");
            assertThat(store.definitions().getFirst().baseQuantitySteps()).isEqualTo(10);
        });
        assertThat(store.definitions().getFirst().baseQuantitySteps()).isEqualTo(12);
        transaction.executeWithoutResult(status -> {
            store.saveDefinition(definition(ProductLine.LINEAR_PERPETUAL, 2, 99), "1", "rollback");
            assertThat(store.definitions().getFirst().baseQuantitySteps()).isEqualTo(12);
            status.setRollbackOnly();
        });
        assertThat(store.definitions().getFirst().baseQuantitySteps()).isEqualTo(12);
        assertThat(new JdbcMarketMakerStrategyOverrideStore(jdbc).definitions().getFirst().baseQuantitySteps())
                .isEqualTo(12);
    }

    @Test void aColdCacheDoesNotPublishUncommittedRowsThatAreLaterRolledBack() {
        var store = new JdbcMarketMakerStrategyOverrideStore(jdbc);
        store.saveDefinition(definition(ProductLine.LINEAR_PERPETUAL, 0, 10), "1", "create");
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.executeWithoutResult(status -> {
            store.saveDefinition(definition(ProductLine.LINEAR_PERPETUAL, 1, 99), "1", "rollback");
            assertThat(store.definitions().getFirst().baseQuantitySteps()).isEqualTo(99);
            status.setRollbackOnly();
        });
        assertThat(store.definitions().getFirst().baseQuantitySteps()).isEqualTo(10);
    }
    private MarketMakerStrategyDefinition definition(ProductLine line, long version, long quantity) {
        return new MarketMakerStrategyDefinition("test-maker", line, false, List.of(2L), List.of("604"),
                quantity, MarginMode.CROSS, 10, 10, 1000, 100000, 3, 0, version);
    }
}
