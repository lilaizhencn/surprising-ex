package com.surprising.gateway.provider.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.gateway.provider.auth.AdminAuditRepository;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="GATEWAY_PRODUCTS_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/gateway_product_settings_qa")
class GatewayProductSettingsIntegrationTest {
    @Configuration @EnableTransactionManagement static class Transactions { }
    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            System.getenv("GATEWAY_PRODUCTS_TEST_JDBC_URL"), "sixline_qa", "qa"));
    private final JwtPrincipal admin = new JwtPrincipal(42,"qa-admin","ACTIVE",List.of("ADMIN"),Instant.now().plusSeconds(60));

    @ParameterizedTest @EnumSource(ProductLine.class)
    void databaseVersionsAuditAndRestartReadAreConsistent(ProductLine product) {
        jdbc.update("UPDATE gateway_product_lines SET enabled=false,version=1 WHERE product_line=?",product.name());
        var audit=mock(AdminAuditRepository.class);
        try (var context=context(audit)) {
            var service=context.getBean(GatewayProductSettings.class);
            assertThat(service.list()).hasSize(6);
            var enabled=service.enable(product,new GatewayProductSettings.Enable(1L,"启用测试"),admin);
            assertThat(enabled.enabled()).isTrue();
            assertThat(enabled.configurationVersion()).isEqualTo(2);
            assertThat(new GatewayProductSettings(jdbc,audit).list()).contains(enabled);
            assertThatThrownBy(()->service.enable(product,new GatewayProductSettings.Enable(1L,"旧版本"),admin)).hasMessageContaining("409");
            assertThatThrownBy(()->service.enable(product,new GatewayProductSettings.Enable(2L," "),admin)).hasMessageContaining("400");
            verify(audit,times(1)).recordRequired(any());
            doThrow(new IllegalStateException("audit unavailable")).when(audit).recordRequired(any());
            assertThatThrownBy(()->service.enable(product,new GatewayProductSettings.Enable(2L,"审计失败"),admin)).hasMessageContaining("audit unavailable");
            assertThat(service.list()).contains(enabled); // 审计失败回滚版本与设置。
        }
    }

    @Test void rejectsCoercionUnknownKeysMissingFieldsAndFractionalVersions() {
        var service=new GatewayProductSettings(jdbc,mock(AdminAuditRepository.class));
        var json=JsonMapper.builder().build();
        for(String value:List.of("{}","{\"expectedVersion\":\"1\",\"reason\":\"x\"}",
                "{\"expectedVersion\":1.5,\"reason\":\"x\"}","{\"expectedVersion\":1,\"reason\":2}",
                "{\"expectedVersion\":1,\"reason\":\"x\",\"enabled\":false}"))
            assertThatThrownBy(()->service.parseEnable(json.readTree(value))).hasMessageContaining("400");
        assertThat(service.parseEnable(json.readTree("{\"expectedVersion\":1,\"reason\":\"x\"}")))
                .isEqualTo(new GatewayProductSettings.Enable(1L,"x"));
    }

    private AnnotationConfigApplicationContext context(AdminAuditRepository audit) {
        var context=new AnnotationConfigApplicationContext();
        context.registerBean(JdbcTemplate.class,()->jdbc);
        context.registerBean(AdminAuditRepository.class,()->audit);
        context.registerBean(DataSourceTransactionManager.class,()->new DataSourceTransactionManager(jdbc.getDataSource()));
        context.register(Transactions.class,GatewayProductSettings.class);
        context.refresh();
        return context;
    }
}
