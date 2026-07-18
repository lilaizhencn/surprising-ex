package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.account.provider.repository.AccountTradeSettlementMonitorRepository;
import java.time.Instant;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Detects the bilateral-settlement special case where only one trade participant has completed.
 */
@Component("accountTradeSettlement")
public class AccountTradeSettlementHealthIndicator implements HealthIndicator {

    private final AccountTradeSettlementMonitorRepository repository;
    private final AccountProperties properties;

    public AccountTradeSettlementHealthIndicator(AccountTradeSettlementMonitorRepository repository,
                                                 AccountProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public Health health() {
        var snapshot = repository.staleIncomplete(
                properties.getKafka().getProductLine(),
                properties.getTradeSettlement().getStaleAfter(), Instant.now());
        Health.Builder builder = snapshot.count() == 0L ? Health.up() : Health.down();
        builder.withDetail("productLine", properties.getKafka().getProductLine().name())
                .withDetail("staleAfterSeconds", properties.getTradeSettlement().getStaleAfter().toSeconds())
                .withDetail("staleIncompleteTrades", snapshot.count());
        if (snapshot.oldestCreatedAt() != null) {
            builder.withDetail("oldestCreatedAt", snapshot.oldestCreatedAt().toString());
        }
        return builder.build();
    }
}
