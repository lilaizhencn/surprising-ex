package com.surprising.aeron.service.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class SymbolMatchingLanesTest {

    @Test
    void sameSymbolSerializesWhileDifferentSymbolsProceed() {
        SymbolMatchingLanes lanes = new SymbolMatchingLanes();
        List<String> submissions = new ArrayList<>();
        CompletableFuture<String> symbol10A = new CompletableFuture<>();

        CompletableFuture<String> first = lanes.enqueue("10", () -> {
            submissions.add("symbol10-A");
            return symbol10A;
        });
        CompletableFuture<String> second = lanes.enqueue("10", () -> {
            submissions.add("symbol10-B");
            return CompletableFuture.completedFuture("B");
        });
        CompletableFuture<String> otherSymbol = lanes.enqueue("11", () -> {
            submissions.add("symbol11-C");
            return CompletableFuture.completedFuture("C");
        });

        assertThat(submissions).containsExactly("symbol10-A", "symbol11-C");
        assertThat(otherSymbol).isCompletedWithValue("C");
        assertThat(second).isNotDone();

        symbol10A.complete("A");

        assertThat(first).isCompletedWithValue("A");
        assertThat(second).isCompletedWithValue("B");
        assertThat(submissions).containsExactly("symbol10-A", "symbol11-C", "symbol10-B");
    }

    @Test
    void failedOperationDoesNotPoisonLane() {
        SymbolMatchingLanes lanes = new SymbolMatchingLanes();
        CompletableFuture<String> failure = new CompletableFuture<>();
        CompletableFuture<String> first = lanes.enqueue("10", () -> failure);
        CompletableFuture<String> second = lanes.enqueue("10",
                () -> CompletableFuture.completedFuture("recovered"));

        failure.completeExceptionally(new IllegalStateException("matcher failed"));

        assertThat(first).isCompletedExceptionally();
        assertThat(second).isCompletedWithValue("recovered");
    }

    @Test
    void barrierWaitsForSnapshotAndLaterBarrierCannotOvertake() {
        SymbolMatchingLanes lanes = new SymbolMatchingLanes();
        List<String> trace = new ArrayList<>();
        CompletableFuture<Void> symbol10 = new CompletableFuture<>();
        CompletableFuture<Void> symbol11 = new CompletableFuture<>();
        CompletableFuture<Void> firstBarrierGate = new CompletableFuture<>();

        lanes.enqueue("10", () -> symbol10);
        lanes.enqueue("11", () -> symbol11);
        CompletableFuture<Void> firstBarrier = lanes.barrier(() -> {
            trace.add("barrier-1");
            return firstBarrierGate;
        });
        CompletableFuture<String> afterBarrier = lanes.enqueue("10", () -> {
            trace.add("symbol10-after");
            return CompletableFuture.completedFuture("done");
        });
        CompletableFuture<Void> secondBarrier = lanes.barrier(() -> {
            trace.add("barrier-2");
            return CompletableFuture.completedFuture(null);
        });

        symbol10.complete(null);
        assertThat(trace).isEmpty();
        symbol11.complete(null);
        assertThat(trace).containsExactly("barrier-1");
        assertThat(afterBarrier).isNotDone();
        assertThat(secondBarrier).isNotDone();

        firstBarrierGate.complete(null);

        assertThat(firstBarrier).isDone();
        assertThat(afterBarrier).isCompletedWithValue("done");
        assertThat(secondBarrier).isDone();
        assertThat(trace).containsExactly("barrier-1", "symbol10-after", "barrier-2");
    }
}
