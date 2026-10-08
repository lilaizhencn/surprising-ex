package com.surprising.gateway.provider.product;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

/** One disposable real single-node Core per product, using real shared-driver Cluster sessions. */
@EnabledIfSystemProperty(named = "gateway.shared-driver.it", matches = "true")
class SharedCommandTransportIntegrationTest {
    @TempDir Path temporary;

    @ParameterizedTest @EnumSource(ProductLine.class)
    void fourSourcesShareTransportWithoutMixingRequestsOrClosingOtherSources(ProductLine product) throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms128m", "-Xmx512m", "-XX:+UseZGC", "--add-opens=java.base/java.util.zip=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED",
                "-Daeron.dir=" + temporary.resolve("core-driver"),
                "-Dsurprising.aeron.product-line=" + product.name(),
                "-Dsurprising.aeron.hostnames=127.0.0.1",
                "-Dsurprising.aeron.data-dir=" + temporary.resolve("data"),
                "-Dsurprising.aeron.service.idle-strategy=BACKOFF",
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                "com.surprising.aeron.service.SurprisingCoreApplication")
                .redirectErrorStream(true).redirectOutput(temporary.resolve("core.log").toFile()).start();
        try (var driver = SurprisingAeronClient.newMediaDriver(temporary.resolve("commands").toString())) {
            var pools = new java.util.ArrayList<AeronClientPool>();
            try {
                for (String source : List.of("account", "order", "trigger", "maintenance"))
                    pools.add(new AeronClientPool(source, product, List.of("127.0.0.1"), "127.0.0.1",
                            Duration.ofSeconds(3), 1, source, driver));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
                while (true) {
                    try {
                        for (var pool : pools)
                            assertThat(pool.query(CoreMessageType.STATE_HASH_QUERY, UUID.randomUUID(), 0, new byte[0]).status())
                                    .isEqualTo(ResponseStatus.OK);
                        break;
                    } catch (RuntimeException exception) {
                        if (!process.isAlive() || System.nanoTime() >= deadline)
                            throw new AssertionError("Core unavailable: " + java.nio.file.Files.readString(temporary.resolve("core.log")), exception);
                        Thread.sleep(50);
                    }
                }
                var pending = new java.util.ArrayList<java.util.concurrent.CompletableFuture<CoreResponse>>();
                byte[] deposit = TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100));
                UUID original = UUID.randomUUID();
                for (int i = 0; i < pools.size(); i++)
                    pending.add(pools.get(i).commandAsync(CoreMessageType.ADJUST_BALANCE,
                            i == 0 ? original : UUID.randomUUID(), 42, deposit));
                for (var result : pending) assertThat(result.get(10, TimeUnit.SECONDS).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                pools.getFirst().close();
                // A retry via another source cannot credit twice; other sessions remain usable.
                assertThat(pools.get(1).command(CoreMessageType.ADJUST_BALANCE, original, 42, deposit).commandStatus())
                        .isEqualTo(ResponseStatus.APPLIED);
                for (int i = 1; i < pools.size(); i++) {
                    var response = pools.get(i).query(CoreMessageType.USER_STATE_QUERY, UUID.randomUUID(), 42, new byte[0]);
                    var user = CoreStateQueryCodec.decodeUserState(response.data());
                    assertThat(user.productLine()).isEqualTo(product);
                    assertThat(user.balances()).hasSize(1);
                    assertThat(user.balances().getFirst().availableUnits()).isEqualTo(400);
                    assertThat(user.balances().getFirst().lockedUnits()).isZero();
                    assertThat(user.positions()).isEmpty();
                }
            } finally { for (var pool : pools) pool.close(); }
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            assertThat(process.isAlive()).isFalse();
        }
    }
}
