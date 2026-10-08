package com.surprising.aeron.client;

import com.surprising.product.api.ProductLine;
import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class BorrowedMediaDriverTest {
    @TempDir Path temporary;

    @ParameterizedTest @EnumSource(ProductLine.class)
    void independentPoolsBorrowOneDriverAndCannotCloseIt(ProductLine product) throws Exception {
        String directory = temporary.resolve(product.name()).toString();
        try (MediaDriver driver = SurprisingAeronClient.newMediaDriver(directory)) {
            var first = pool("account", product, driver);
            try (var second = pool("order", product, driver)) {
                var accessor = AeronClientPool.class.getDeclaredMethod("sharedMediaDriver");
                accessor.setAccessible(true);
                assertThat(accessor.invoke(first)).isSameAs(driver);
                assertThat(accessor.invoke(second)).isSameAs(driver);
                assertThat(first.commandMailboxCapacity()).isEqualTo(256);
                assertThat(second.controlMailboxCapacity()).isEqualTo(64);
                first.close();
                first.close();
                assertThat(accessor.invoke(second)).isSameAs(driver);
                // A real new Aeron client still connects after another borrowing pool closes.
                try (Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(directory))) {
                    assertThat(aeron.isClosed()).isFalse();
                }
            } finally { first.close(); }
            try (Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(directory))) {
                assertThat(aeron.isClosed()).isFalse();
            }
        }
        assertThat(Path.of(directory)).doesNotExist();
    }

    private static AeronClientPool pool(String name, ProductLine product, MediaDriver driver) {
        return new AeronClientPool(name, product, List.of("localhost"), "localhost",
                Duration.ofSeconds(1), name, "epoch", AeronClientCapacity.defaults(),
                () -> { throw new IllegalStateException("no cluster needed"); }, false, driver);
    }
}
