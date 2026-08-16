package com.surprising.aeron.service.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.state.CoreOrderState;
import com.surprising.aeron.service.state.CoreOrderStatus;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class DeterministicExchangeCoreAdapterTest {

    @Test
    void cancelBatchStopsAtFirstFailureAndReturnsSuccessfulPrefix() {
        List<CoreOrderState> orders = List.of(order(1), order(2), order(3));
        List<Long> submissions = new ArrayList<>();
        CompletableFuture<CoreMatchingResult> first = new CompletableFuture<>();
        CompletableFuture<CoreMatchingResult> second = new CompletableFuture<>();

        CompletableFuture<DeterministicExchangeCoreAdapter.CancelBatchOutcome> outcomeFuture =
                DeterministicExchangeCoreAdapter.cancelBatchOrderedAsync(orders, order -> {
                    submissions.add(order.orderId());
                    return order.orderId() == 1 ? first : second;
                });

        assertThat(submissions).containsExactly(1L);
        first.complete(result(true, "SUCCESS"));
        assertThat(submissions).containsExactly(1L, 2L);
        second.complete(result(false, "MATCHING_INVALID_ORDER_ID"));

        DeterministicExchangeCoreAdapter.CancelBatchOutcome outcome = outcomeFuture.join();
        assertThat(submissions).containsExactly(1L, 2L);
        assertThat(outcome.successfulPrefix()).extracting(CoreMatchingResult::resultCode)
                .containsExactly("SUCCESS");
        assertThat(outcome.failedResult().resultCode()).isEqualTo("MATCHING_INVALID_ORDER_ID");
        assertThat(outcome.exception()).isNull();
        assertThat(outcome.results()).extracting(CoreMatchingResult::resultCode)
                .containsExactly("SUCCESS", "MATCHING_INVALID_ORDER_ID", "NOT_SUBMITTED");
    }

    private static CoreOrderState order(long orderId) {
        return new CoreOrderState(orderId, ProductLine.SPOT, 7, "BTC-USDT", 1,
                CoreOrderSide.BUY, 100, 1, 0, 1, false, CoreOrderStatus.OPEN, 1);
    }

    private static CoreMatchingResult result(boolean accepted, String resultCode) {
        return new CoreMatchingResult(accepted, resultCode, List.of());
    }
}
