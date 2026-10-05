package com.surprising.derivatives.lifecycle;

import static org.assertj.core.api.Assertions.*;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.service.AdlRuntimeConfigService;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.provider.config.RiskProperties;
import java.util.Map;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Dedicated local PostgreSQL only: persisted versions, reload and atomic validation across all derivatives. */
@EnabledIfEnvironmentVariable(named = "LIFECYCLE_TEST_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sixline_qa")
class LifecycleBusinessSettingsIntegrationTest {
    private record Instance(LifecycleBusinessSettingsService service, FundingProperties funding,
            LiquidationProperties liquidation, InsuranceProperties insurance, AdlProperties adl) {}
    private Instance instance(JdbcTemplate jdbc, ProductLine line) {
        var risk = new RiskProperties(); risk.setProductLine(line);
        var env = new MockEnvironment().withProperty("surprising.liquidation.execution.enabled", "false")
                .withProperty("surprising.insurance.coverage.batch-size", "9999")
                .withProperty("surprising.funding.calculation.publish-delay-ms", "999999")
                .withProperty("surprising.liquidation.aeron.client-connections", "3");
        var infra = new LifecycleInfrastructureConfiguration();
        var liquidation = infra.liquidationProperties(env, risk);
        assertThat(liquidation.getAeron().getClientConnections()).isEqualTo(3);
        var insurance = infra.insuranceProperties(env, risk);
        var adl = infra.adlProperties(risk);
        var funding = new FundingConfiguration().fundingProperties(env, risk);
        var beans = new StaticListableBeanFactory(line.isFundingProduct() ? Map.of("funding", funding) : Map.of());
        var service = new LifecycleBusinessSettingsService(jdbc, JsonMapper.builder().findAndAddModules().build(), risk,
                beans.getBeanProvider(FundingProperties.class), liquidation, insurance, adl);
        service.initialize();
        return new Instance(service, funding, liquidation, insurance, adl);
    }

    @ParameterizedTest @EnumSource(value = ProductLine.class, names = "SPOT", mode = EnumSource.Mode.EXCLUDE)
    void settingsSurviveRestartReloadAndRejectPartialOrStaleWrites(ProductLine line) {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(System.getenv("LIFECYCLE_TEST_JDBC_URL"), "sixline_qa", "qa"));
        jdbc.update("DELETE FROM lifecycle_business_settings WHERE product_line=?", line.name());
        var first = instance(jdbc, line);
        assertThat(first.service.current().settings()).isEqualTo(LifecycleBusinessSettings.initial());
        assertThat(first.liquidation.getExecution().isEnabled()).isTrue();
        assertThat(first.insurance.getCoverage().getBatchSize()).isEqualTo(100);
        var second = instance(jdbc, line);
        var initial = first.service.current();
        var modified = new LifecycleBusinessSettings(
                new LifecycleBusinessSettings.Funding(false, false, true, 75, 3500, 5000, 100, 30, 4, 6000),
                new LifecycleBusinessSettings.Liquidation(false, 2500, 100, 32, 2, 4096),
                new LifecycleBusinessSettings.Insurance(false, 150, 42),
                new LifecycleBusinessSettings.Adl(false, 200, 40, 10, 3));
        first.service.save(modified, initial.version(), "42", "hot configuration verification");
        assertThat(first.service.current().version()).isEqualTo(initial.version() + 1);
        assertThat(first.liquidation.getExecution().isEnabled()).isFalse();
        assertThat(first.insurance.getCoverage().getBatchSize()).isEqualTo(42);
        assertThat(first.adl.getScanner().getScanDelayMs()).isEqualTo(200);
        if (line.isFundingProduct()) assertThat(first.funding.getCalculation().getPublishDelayMs()).isEqualTo(75);
        second.service.reload();
        assertThat(second.service.current()).isEqualTo(first.service.current());
        assertThat(instance(jdbc, line).service.current()).isEqualTo(first.service.current());
        assertThatThrownBy(() -> second.service.save(initial.settings(), initial.version(), "43", "stale edit"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        var api = new AdlRuntimeConfigService(first.service);
        assertThatThrownBy(() -> api.update("42", first.service.current().version(), true, 10L, null, null,
                100, 10, 3, "invalid interval")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> api.update("42", first.service.current().version(), true, 100L, null, null,
                100, 100, 11, "invalid candidate budget")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> first.service.save(initial.settings(), first.service.current().version(), "42", " "))
                .isInstanceOf(IllegalArgumentException.class);
        second.service.reload();
        assertThat(second.service.current()).isEqualTo(first.service.current());
        assertThat(first.service.current().settings()).isEqualTo(modified);
    }
}
