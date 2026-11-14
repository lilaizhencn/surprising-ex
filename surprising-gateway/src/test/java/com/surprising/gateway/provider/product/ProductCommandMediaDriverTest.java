package com.surprising.gateway.provider.product;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.service.AccountAeronGateway;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.derivatives.lifecycle.DerivativesAeronClient;
import com.surprising.gateway.provider.config.GatewayProductsHealth;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.service.MarkPriceCorePublisher;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.trading.maintenance.service.MaintenanceAeronGateway;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.service.OrderAeronGateway;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.service.TriggerOrderAeronGateway;
import io.aeron.driver.MediaDriver;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class ProductCommandMediaDriverTest {
    @Test
    void gatewayParentOwnsSingleTransportSharedAcrossAllProductContexts() {
        String previous = System.getProperty("surprising.aeron.client.connect-timeout-ms");
        System.setProperty("surprising.aeron.client.connect-timeout-ms", "50");
        String directory;
        try (var parent = new AnnotationConfigApplicationContext()) {
            parent.registerBean("productCommandMediaDriver", MediaDriver.class,
                    () -> new GatewayProductsHealth().productCommandMediaDriver(),
                    bean -> bean.setDestroyMethodName("close"));
            parent.refresh();
            var driver = parent.getBean("productCommandMediaDriver", MediaDriver.class);
            directory = driver.aeronDirectoryName();

            for (ProductLine product : ProductLine.values()) {
                try (var child = new AnnotationConfigApplicationContext()) {
                    child.setParent(parent);
                    var account = new AccountProperties();
                    account.getKafka().setProductLine(product);
                    account.getAeron().setClientConnections(1);
                    var order = new TradingOrderProperties();
                    order.getKafka().setProductLine(product);
                    order.getAeron().setClientConnections(1);
                    var trigger = new TriggerProperties();
                    trigger.setProductLine(product);
                    trigger.getAeron().setClientConnections(1);
                    var risk = new RiskProperties();
                    risk.setProductLine(product);
                    risk.getAeron().setClientConnections(4);
                    var liquidation = new LiquidationProperties();
                    liquidation.setProductLine(product);
                    var insurance = new InsuranceProperties();
                    insurance.setProductLine(product);
                    var adl = new AdlProperties();
                    adl.setProductLine(product);
                    var markPrice = new MarkPriceProperties();
                    markPrice.setProductLine(product);
                    child.registerBean(AccountProperties.class, () -> account);
                    child.registerBean(TradingOrderProperties.class, () -> order);
                    child.registerBean(TriggerProperties.class, () -> trigger);
                    child.registerBean(RiskProperties.class, () -> risk);
                    child.registerBean(LiquidationProperties.class, () -> liquidation);
                    child.registerBean(InsuranceProperties.class, () -> insurance);
                    child.registerBean(AdlProperties.class, () -> adl);
                    child.registerBean(MarkPriceProperties.class, () -> markPrice);
                    child.register(AccountAeronGateway.class, OrderAeronGateway.class,
                            TriggerOrderAeronGateway.class, MaintenanceAeronGateway.class,
                            DerivativesAeronClient.class, MarkPriceCorePublisher.class);
                    child.refresh();

                    var pools = new java.util.HashSet<>();
                    var sources = new java.util.HashSet<>();
                    for (Class<?> type : new Class<?>[]{AccountAeronGateway.class, OrderAeronGateway.class,
                            TriggerOrderAeronGateway.class, MaintenanceAeronGateway.class,
                            MarkPriceCorePublisher.class}) {
                        Object pool = ReflectionTestUtils.getField(child.getBean(type), "clients");
                        pools.add(pool);
                        sources.add(ReflectionTestUtils.getField(pool, "sourceIdentity"));
                        assertThat(ReflectionTestUtils.getField(pool, "productLine")).isEqualTo(product);
                        assertThat(((AtomicReference<?>) ReflectionTestUtils.getField(pool, "mediaDriver")).get())
                                .isSameAs(driver);
                    }
                    Object derivativesPool = ReflectionTestUtils.getField(child.getBean(DerivativesAeronClient.class), "pool");
                    pools.add(derivativesPool);
                    sources.add(ReflectionTestUtils.getField(derivativesPool, "sourceIdentity"));
                    assertThat(ReflectionTestUtils.getField(derivativesPool, "productLine")).isEqualTo(product);
                    assertThat(((AtomicReference<?>) ReflectionTestUtils.getField(derivativesPool, "mediaDriver")).get())
                            .isSameAs(driver);
                    assertThat(pools).hasSize(6);
                    assertThat(sources).hasSize(6);
                }
                // Closing a product child context must not shut down the shared Gateway MediaDriver.
                assertThat(Path.of(directory)).exists();
            }
        } finally {
            if (previous == null) System.clearProperty("surprising.aeron.client.connect-timeout-ms");
            else System.setProperty("surprising.aeron.client.connect-timeout-ms", previous);
        }
        assertThat(Path.of(directory)).doesNotExist();
    }
}
