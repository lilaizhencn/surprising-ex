package com.surprising.aeron.service.matching;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

final class SymbolMatchingLanes {

    private final Object monitor = new Object();
    private final Map<String, CompletableFuture<Void>> laneTails = new HashMap<>();
    private CompletableFuture<Void> globalTail = CompletableFuture.completedFuture(null);
    private final Executor dispatcher;
    private boolean closing;
    private CompletableFuture<Void> closeFuture;

    SymbolMatchingLanes() {
        this(Runnable::run);
    }

    SymbolMatchingLanes(Executor dispatcher) {
        this.dispatcher = dispatcher;
    }

    <T> CompletableFuture<T> enqueue(String symbol, Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> predecessor;
        synchronized (monitor) {
            if (closing) {
                return CompletableFuture.failedFuture(new IllegalStateException("matching lanes are closed"));
            }
            predecessor = CompletableFuture.allOf(
                    laneTails.getOrDefault(symbol, CompletableFuture.completedFuture(null)), globalTail);
            laneTails.put(symbol, gate);
        }
        return runAfter(predecessor, operation, gate, dispatcher);
    }

    <T> CompletableFuture<T> barrier(Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> predecessor;
        synchronized (monitor) {
            if (closing) {
                return CompletableFuture.failedFuture(new IllegalStateException("matching lanes are closed"));
            }
            List<CompletableFuture<Void>> tails = new ArrayList<>(laneTails.values());
            tails.add(globalTail);
            predecessor = CompletableFuture.allOf(tails.toArray(CompletableFuture[]::new));
            globalTail = gate;
        }
        return runAfter(predecessor, operation, gate, dispatcher);
    }

    CompletableFuture<Void> quiesce() {
        synchronized (monitor) {
            if (closeFuture != null) {
                return closeFuture;
            }
            closing = true;
            List<CompletableFuture<Void>> tails = new ArrayList<>(laneTails.values());
            tails.add(globalTail);
            closeFuture = CompletableFuture.allOf(tails.toArray(CompletableFuture[]::new));
            return closeFuture;
        }
    }

    private static <T> CompletableFuture<T> runAfter(CompletableFuture<Void> predecessor,
                                                       Supplier<CompletableFuture<T>> operation,
                                                       CompletableFuture<Void> gate,
                                                       Executor dispatcher) {
        return predecessor.handle((ignored, failure) -> null).thenComposeAsync(ignored -> {
            CompletableFuture<T> result;
            try {
                result = operation.get();
            } catch (RuntimeException exception) {
                gate.complete(null);
                return CompletableFuture.failedFuture(exception);
            }
            return result.whenComplete((value, failure) -> gate.complete(null));
        }, dispatcher);
    }
}
