package com.surprising.aeron.benchmarks.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ResponseStatus;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ClusterAsyncPairTest {
    private static final CoreResponse OK = new CoreResponse(ResponseStatus.APPLIED, 1, 1);

    @Test void independentOrdersAreBothSubmittedBeforeEitherReplyAndTimedSeparately() {
        var price = new CompletableFuture<Void>();
        var sell = new CompletableFuture<CoreResponse>();
        var buy = new CompletableFuture<CoreResponse>();
        var actions = new ArrayList<String>();
        var clock = new AtomicLong(100);
        var sellDone = ClusterAsyncPair.independent(price,
                () -> { actions.add("sell"); return sell; },
                (r, ns) -> actions.add("sell-terminal:" + ns), clock::get);
        var buyDone = ClusterAsyncPair.independent(price,
                () -> { actions.add("buy"); return buy; },
                (r, ns) -> actions.add("buy-terminal:" + ns), clock::get);
        assertThat(actions).isEmpty();
        price.complete(null);
        assertThat(actions).containsExactlyInAnyOrder("sell", "buy");
        assertThat(sellDone).isNotDone();
        assertThat(buyDone).isNotDone();
        clock.set(130);
        buy.complete(OK);
        assertThat(buyDone).isCompleted();
        assertThat(sellDone).isNotDone();
        clock.set(170);
        sell.complete(OK);
        assertThat(actions).contains("buy-terminal:30", "sell-terminal:70");
    }

    @Test void independentFailureDoesNotSuppressAnotherAlreadySubmittedOrder() {
        var sell = new CompletableFuture<CoreResponse>();
        var buy = new CompletableFuture<CoreResponse>();
        var ready = CompletableFuture.<Void>completedFuture(null);
        var sellDone = ClusterAsyncPair.independent(ready, () -> sell, (r, ns) -> {}, System::nanoTime);
        var buyDone = ClusterAsyncPair.independent(ready, () -> buy, (r, ns) -> {}, System::nanoTime);
        sell.completeExceptionally(new IllegalStateException("rejected"));
        buy.complete(OK);
        assertThat(sellDone).isCompletedExceptionally();
        assertThat(buyDone).isCompleted();
    }

    @Test void priceDependencyDoesNotBlockAndEachOrderIsTimedAtItsOwnTerminal() {
        var price = new CompletableFuture<Void>();
        var maker = new CompletableFuture<CoreResponse>();
        var taker = new CompletableFuture<CoreResponse>();
        var actions = new ArrayList<String>();
        var clock = new AtomicLong();
        var done = ClusterAsyncPair.start(price,
                () -> { actions.add("maker"); return maker; },
                () -> { actions.add("taker"); return taker; },
                (r, ns) -> actions.add("maker-terminal:" + ns),
                (r, ns) -> actions.add("taker-terminal:" + ns), clock::get);
        assertThat(actions).isEmpty();
        assertThat(done).isNotDone();
        clock.set(1000);
        price.complete(null);
        assertThat(actions).containsExactly("maker");
        clock.set(1050);
        maker.complete(OK);
        assertThat(actions).containsExactly("maker", "maker-terminal:50", "taker");
        clock.set(1080);
        taker.complete(OK);
        clock.set(100000); // Delay in collecting the future must not inflate either terminal latency.
        done.join();
        assertThat(actions).containsExactly("maker", "maker-terminal:50", "taker", "taker-terminal:30");
    }

    @Test void rejectedMakerCannotStartTaker() {
        var failure = new IllegalStateException("maker rejected");
        var done = ClusterAsyncPair.start(CompletableFuture.completedFuture(null),
                () -> CompletableFuture.completedFuture(OK),
                () -> { throw new AssertionError("taker must not run"); },
                (r, ns) -> { throw failure; }, (r, ns) -> { }, System::nanoTime);
        assertThatThrownBy(done::join).hasCause(failure);
    }

    @Test void workerPartitionsAlwaysShareTheSameGlobal256Window() {
        for (int workers : new int[]{1, 2, 3, 4}) {
            int sum = 0;
            for (int worker = 0; worker < workers; worker++) sum += ClusterCapacityMain.slotsForWorker(256, workers, worker);
            assertThat(sum).isEqualTo(256);
        }
    }
}
