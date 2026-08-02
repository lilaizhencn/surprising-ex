package com.surprising.funding.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCursor;
import com.surprising.funding.provider.model.FundingPaymentPage;
import com.surprising.funding.provider.model.FundingSettlementWork;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
class FundingPaymentCandidateRepositoryTest {

    @Test
    void paymentCandidatesReadOnlyFromReadyAccountSnapshot() {
        InstrumentSnapshotCache snapshotCache = new InstrumentSnapshotCache();
        snapshotCache.replace(ProductLine.LINEAR_PERPETUAL, List.of(), java.util.Map.of());
        PerpetualAccountStateSnapshotCache accountCache = new PerpetualAccountStateSnapshotCache();
        accountCache.markReady();
        FundingPaymentCandidateRepository repository =
                new FundingPaymentCandidateRepository(new FundingProperties(), snapshotCache, accountCache);
        FundingSettlementWork settlement = new FundingSettlementWork(
                22L, "BTC-USDT", Instant.parse("2026-07-01T00:00:00Z"), 100L,
                7L, 65_000L, new FundingPaymentCursor(1001L, "CROSS", "NET"));

        FundingPaymentPage page = repository.findPage(settlement, 500);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }
}
