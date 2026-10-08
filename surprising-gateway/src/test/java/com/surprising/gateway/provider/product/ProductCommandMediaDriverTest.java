package com.surprising.gateway.provider.product;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.service.AccountAeronGateway;
import com.surprising.gateway.product.ProductBusinessConfiguration;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.maintenance.MaintenanceAeronGateway;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.service.OrderAeronGateway;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.service.TriggerOrderAeronGateway;
import io.aeron.driver.MediaDriver;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class ProductCommandMediaDriverTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void productContextSharesOnlyTransportAndClosesItAfterItsPools(ProductLine product) {
        String previous = System.getProperty("surprising.aeron.client.connect-timeout-ms");
        System.setProperty("surprising.aeron.client.connect-timeout-ms", "50");
        String directory;
        try (var context = new AnnotationConfigApplicationContext()) {
            var account = new AccountProperties();
            account.getKafka().setProductLine(product);
            account.getAeron().setClientConnections(1);
            var order = new TradingOrderProperties();
            order.getKafka().setProductLine(product);
            order.getAeron().setClientConnections(1);
            var trigger = new TriggerProperties();
            trigger.setProductLine(product);
            trigger.getAeron().setClientConnections(1);
            context.registerBean(AccountProperties.class, () -> account);
            context.registerBean(TradingOrderProperties.class, () -> order);
            context.registerBean(TriggerProperties.class, () -> trigger);
            context.registerBean("productCommandMediaDriver", MediaDriver.class,
                    () -> new ProductBusinessConfiguration().productCommandMediaDriver(account),
                    bean -> bean.setDestroyMethodName("close"));
            context.register(AccountAeronGateway.class, OrderAeronGateway.class,
                    TriggerOrderAeronGateway.class, MaintenanceAeronGateway.class);
            context.refresh();
            var driver = context.getBean(MediaDriver.class);
            directory = driver.aeronDirectoryName();
            var pools = new java.util.HashSet<>();
            var sources = new java.util.HashSet<>();
            for (Class<?> type : new Class<?>[]{AccountAeronGateway.class, OrderAeronGateway.class,
                    TriggerOrderAeronGateway.class, MaintenanceAeronGateway.class}) {
                Object pool = ReflectionTestUtils.getField(context.getBean(type), "clients");
                pools.add(pool);
                sources.add(ReflectionTestUtils.getField(pool, "sourceIdentity"));
                assertThat(ReflectionTestUtils.getField(pool, "productLine")).isEqualTo(product);
                assertThat(((AtomicReference<?>) ReflectionTestUtils.getField(pool, "mediaDriver")).get())
                        .isSameAs(driver);
            }
            assertThat(pools).hasSize(4);
            assertThat(sources).hasSize(4);
        } finally {
            if (previous == null) System.clearProperty("surprising.aeron.client.connect-timeout-ms");
            else System.setProperty("surprising.aeron.client.connect-timeout-ms", previous);
        }
        assertThat(Path.of(directory)).doesNotExist();
    }
}
