package com.surprising.account.provider.service;

import com.surprising.account.api.model.BalanceAdjustmentRequest;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 本地环境自动为固定做市账号注入充足测试资金（支持每次启动清理 Aeron 后自动补齐）。 */
@Component
@Profile("local")
public class LocalMakerAccountSeeder implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(LocalMakerAccountSeeder.class);

    private static final long USDT_TARGET_UNITS = 1_000_000_000_000_000L; // 10,000,000 USDT (1e8)
    private static final long USDT_MIN_THRESHOLD = 100_000_000_000_000L; // 1,000,000 USDT

    private record SeedTarget(long userId, String asset, long targetUnits, long minThreshold) {}

    private static final List<SeedTarget> PERP_TARGETS = List.of(
            new SeedTarget(101L, "USDT", USDT_TARGET_UNITS, USDT_MIN_THRESHOLD),
            new SeedTarget(102L, "USDT", USDT_TARGET_UNITS, USDT_MIN_THRESHOLD),
            new SeedTarget(103L, "USDT", USDT_TARGET_UNITS, USDT_MIN_THRESHOLD)
    );

    private static final List<SeedTarget> SPOT_TARGETS = List.of(
            new SeedTarget(201L, "USDT", USDT_TARGET_UNITS, USDT_MIN_THRESHOLD),
            new SeedTarget(201L, "BTC", 10_000_000_000L, 1_000_000_000L), // 100 BTC (1e8)
            new SeedTarget(202L, "USDT", USDT_TARGET_UNITS, USDT_MIN_THRESHOLD),
            new SeedTarget(202L, "ETH", 2_000_000_000_000_000_000L, 200_000_000_000_000_000L), // 2 ETH (1e18)
            new SeedTarget(203L, "USDT", USDT_TARGET_UNITS, USDT_MIN_THRESHOLD),
            new SeedTarget(203L, "SOL", 1_000_000_000_000L, 100_000_000_000L) // 10,000 SOL (1e8)
    );

    private final AccountProperties properties;
    private final AccountAeronGateway aeron;
    private final AccountCommandGateway commandGateway;

    public LocalMakerAccountSeeder(
            AccountProperties properties,
            AccountAeronGateway aeron,
            AccountCommandGateway commandGateway) {
        this.properties = properties;
        this.aeron = aeron;
        this.commandGateway = commandGateway;
    }

    @Override
    public void afterSingletonsInstantiated() {
        ProductLine product = properties.getProductLine();
        List<SeedTarget> targets = switch (product) {
            case LINEAR_PERPETUAL -> PERP_TARGETS;
            case SPOT -> SPOT_TARGETS;
            default -> List.of();
        };
        if (targets.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("local-maker-seeder-" + product.name().toLowerCase()).start(() -> seedWithRetry(product, targets));
    }

    private void seedWithRetry(ProductLine product, List<SeedTarget> targets) {
        for (int attempt = 1; attempt <= 60; attempt++) {
            try {
                for (SeedTarget target : targets) {
                    long available = availableUnits(target.userId(), target.asset());
                    if (available < target.minThreshold()) {
                        long delta = target.targetUnits() - available;
                        String ref = "local-auto-seed-" + product.name().toLowerCase()
                                + "-" + target.userId() + "-" + target.asset().toLowerCase()
                                + "-" + System.currentTimeMillis();
                        commandGateway.adjustBalance(
                                new BalanceAdjustmentRequest(target.userId(), target.asset(), delta, ref, "local maker auto seed"),
                                "3", "system_admin");
                        log.info("Auto-seeded local maker account productLine={} userId={} asset={} deltaUnits={}",
                                product, target.userId(), target.asset(), delta);
                    }
                }
                return;
            } catch (Exception ex) {
                log.warn("Local maker seeder attempt {} failed for {}: {}", attempt, product, ex.getMessage());
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.warn("Local maker seeder did not complete within 60 attempts for {}", product);
    }

    private long availableUnits(long userId, String asset) {
        CoreUserStateView state = aeron.userState(userId);
        if (state == null || state.balances() == null) {
            return 0L;
        }
        for (CoreBalanceView balance : state.balances()) {
            if (asset.equalsIgnoreCase(balance.asset())) {
                return balance.availableUnits();
            }
        }
        return 0L;
    }
}
