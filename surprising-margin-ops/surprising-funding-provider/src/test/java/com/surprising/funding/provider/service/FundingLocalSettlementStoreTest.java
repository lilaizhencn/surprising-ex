package com.surprising.funding.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingPaymentPage;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FundingLocalSettlementStoreTest {

    @Test
    void persistsCursorAndReplaysUnpublishedPaymentAfterRestart() throws Exception {
        var directory = Files.createTempDirectory("funding-settlement-store-");
        Instant fundingTime = Instant.parse("2026-07-01T08:00:00Z");
        FundingRateResponse rate = new FundingRateResponse("BTC-USDT", 1L, 100L, 90L, 10L,
                fundingTime, 8, "PREDICTED", fundingTime.minusSeconds(1L));
        MarkPriceEvent mark = new MarkPriceEvent(ProductLine.LINEAR_PERPETUAL, "BTC-USDT", 7L,
                65_000L, 65_000L, BigDecimal.valueOf(65_000L), BigDecimal.valueOf(65_000L),
                null, null, null, null, null, null, fundingTime, 0L, null, 0L, null, null,
                1L, PriceStatus.HEALTHY, fundingTime, fundingTime);
        FundingPaymentCandidate candidate = new FundingPaymentCandidate(1001L, "BTC-USDT", MarginMode.CROSS,
                PositionSide.NET, "USDT", 10L, 100_000L, 100L, -10L);

        try (var first = new FundingLocalSettlementStore(directory, new ObjectMapper())) {
            var work = first.begin(rate, mark);
            var page = new FundingPaymentPage(List.of(candidate),
                    com.surprising.funding.provider.model.FundingPaymentCursor.from(candidate), false);
            var payments = first.appendPage(work, page);
            assertThat(payments).hasSize(1);
            assertThat(first.pendingPayments(10)).extracting(FundingLocalSettlementStore.PendingPayment::commandId)
                    .containsExactly(payments.getFirst().commandId());
            assertThat(first.begin(rate, mark).settlementId()).isEqualTo(work.settlementId());
        }

        try (var restarted = new FundingLocalSettlementStore(directory, new ObjectMapper())) {
            var pending = restarted.pendingPayments(10);
            assertThat(pending).hasSize(1);
            restarted.markPublished(pending.getFirst().commandId());
            assertThat(restarted.pendingPayments(10)).isEmpty();
            assertThat(restarted.activeSettlements()).isEmpty();
        }
    }
}
