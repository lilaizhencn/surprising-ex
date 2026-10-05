package com.surprising.price.settings;

import static org.assertj.core.api.Assertions.*;
import com.surprising.product.api.ProductLine;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.mark.config.MarkPriceProperties;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "PRICE_TEST_JDBC_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/sixline_qa")
class PriceBusinessSettingsIntegrationTest {
    private record Instance(PriceBusinessSettingsService service, IndexPriceProperties index, MarkPriceProperties mark) {}
    private Instance instance(JdbcTemplate jdbc, ProductLine line) {
        var env = new MockEnvironment().withProperty("surprising.price.index.kafka.product-line", line.name())
                .withProperty("surprising.price.mark.kafka.product-line", line.name())
                .withProperty("surprising.price.index.calculation.poll-delay-ms", "50")
                .withProperty("surprising.price.index.fiat.enabled", "true")
                .withProperty("surprising.price.index.http.proxy-port", "12345");
        var infrastructure = new PriceInfrastructureConfiguration();
        var index = infrastructure.indexPriceProperties(env); var mark = infrastructure.markPriceProperties(env);
        assertThat(index.getHttp().getProxyPort()).isEqualTo(12345);
        var service = new PriceBusinessSettingsService(jdbc, JsonMapper.builder().findAndAddModules().build(), index, mark);
        service.initialize(); return new Instance(service, index, mark);
    }
    @ParameterizedTest @EnumSource(ProductLine.class)
    void persistsReloadsAndRejectsInvalidSettingsWithoutChangingRuntime(ProductLine line) {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(System.getenv("PRICE_TEST_JDBC_URL"), "sixline_qa", "qa"));
        jdbc.update("DELETE FROM price_business_settings WHERE product_line=?", line.name());
        var first = instance(jdbc, line); var second = instance(jdbc, line);
        assertThat(first.index.getCalculation().getPollDelayMs()).isEqualTo(1000);
        assertThat(first.index.getFiat().isEnabled()).isFalse();
        var old = first.service.current(); var settings = old.settings();
        var json = JsonMapper.builder().build();
        settings.put("indexPollDelayMs", json.valueToTree(100));
        settings.put("markClampRatio", json.valueToTree(0.02));
        settings.put("websocketEnabled", json.valueToTree(false));
        first.service.save(settings, old.version(), "42", "six-line price settings verification");
        assertThat(first.index.getCalculation().getPollDelayMs()).isEqualTo(100);
        assertThat(first.index.getWebSocket().isEnabled()).isFalse();
        assertThat(first.mark.getCalculation().getClampRatio()).isEqualByComparingTo("0.02");
        second.service.reload();
        assertThat(second.service.current()).isEqualTo(first.service.current());
        assertThat(instance(jdbc, line).service.current()).isEqualTo(first.service.current());
        assertThatThrownBy(() -> second.service.save(old.settings(), old.version(), "43", "stale writer"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        for (var invalid : java.util.List.of("indexPollDelayMs", "indexScale", "markClampRatio")) {
            var bad = first.service.current().settings(); bad.put(invalid, json.valueToTree(-1));
            assertThatThrownBy(() -> first.service.save(bad, first.service.current().version(), "42", "invalid"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var missingFeed = first.service.current().settings(); missingFeed.put("fiatEnabled", json.valueToTree(true));
        assertThatThrownBy(() -> first.service.save(missingFeed, first.service.current().version(), "42", "missing feed"))
                .isInstanceOf(IllegalArgumentException.class);
        var unknown = first.service.current().settings(); unknown.put("notASetting", json.valueToTree(1));
        assertThatThrownBy(() -> first.service.save(unknown, first.service.current().version(), "42", "unknown setting"))
                .isInstanceOf(IllegalArgumentException.class);
        second.service.reload(); assertThat(second.service.current()).isEqualTo(first.service.current());
        assertThat(first.service.current().version()).isEqualTo(old.version() + 1);
    }
}
