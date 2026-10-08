package com.surprising.marketmaker.provider.repository;

import static org.assertj.core.api.Assertions.*;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.config.MarketMakerBusinessSettings;
import com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class MarketMakerRunEventRepositoryTest {
    @Test void diagnosticEventsAreBoundedAndPaginationKeepsProductsAndEqualTimestampsSeparate() {
        var store = new InMemoryMarketMakerRunEventRepository();
        var time = Instant.parse("2026-10-08T00:00:00Z");
        for (int i = 0; i < 2050; i++) store.record(new MarketMakerRunEventRepository.MarketMakerRunEventWrite(
                "test-maker", i % 2 == 0 ? ProductLine.SPOT : ProductLine.LINEAR_PERPETUAL, "1", 2L, "node", i,
                "CYCLE_SUCCESS", 0, 0, 0, null, "x".repeat(1500), "trace", time));
        var page = store.findPage(ProductLine.SPOT, "TEST-MAKER", "1", 2L, "CYCLE_SUCCESS", 1000, null, null);
        assertThat(page.items()).hasSize(1000);
        assertThat(page.items()).allSatisfy(e -> {
            assertThat(e.productLine()).isEqualTo(ProductLine.SPOT);
            assertThat(e.eventId()).isGreaterThan(2);
            assertThat(e.errorMessage()).hasSize(1000);
        });
        var rest = store.findPage(ProductLine.SPOT, null, null, null, null, 1000, page.nextCursor(), null);
        assertThat(rest.items()).hasSize(24);
        assertThat(rest.items()).doesNotContainAnyElementsOf(page.items());
        assertThat(rest.hasMore()).isFalse();
        assertThat(new InMemoryMarketMakerRunEventRepository().find(null, null, null, null, null, 100)).isEmpty();
    }

}
