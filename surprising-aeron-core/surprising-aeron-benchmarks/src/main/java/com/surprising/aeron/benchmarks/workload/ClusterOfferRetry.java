package com.surprising.aeron.benchmarks.workload;

import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.protocol.CoreResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Workload-side retry for offers known not to have entered the Cluster Log. */
final class ClusterOfferRetry {
    private ClusterOfferRetry() { }

    static CompletableFuture<CoreResponse> submit(
            Supplier<CompletableFuture<CoreResponse>> operation, Runnable onRetry) {
        return submit(operation, onRetry, TimeUnit.SECONDS.toNanos(10));
    }

    static CompletableFuture<CoreResponse> submit(
            Supplier<CompletableFuture<CoreResponse>> operation, Runnable onRetry, long timeoutNanos) {
        CompletableFuture<CoreResponse> result = new CompletableFuture<>();
        attempt(operation, onRetry, System.nanoTime() + timeoutNanos, result);
        return result;
    }

    private static void attempt(Supplier<CompletableFuture<CoreResponse>> operation,
                                Runnable onRetry, long deadline, CompletableFuture<CoreResponse> result) {
        if (result.isDone()) return;
        CompletableFuture<CoreResponse> offered;
        try { offered = operation.get(); }
        catch (RuntimeException failure) { offered = CompletableFuture.failedFuture(failure); }
        offered.whenComplete((response, failure) -> {
            if (failure == null) { result.complete(response); return; }
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof CoreCommandOutcome.NotAcceptedException rejected
                    && (rejected.rejection().reason() == CoreCommandOutcome.NotAcceptedReason.ADMIN_ACTION
                    || rejected.rejection().reason() == CoreCommandOutcome.NotAcceptedReason.CLIENT_BACKPRESSURED)
                    && System.nanoTime() - deadline < 0) {
                onRetry.run();
                CompletableFuture.delayedExecutor(1, TimeUnit.MILLISECONDS)
                        .execute(() -> attempt(operation, onRetry, deadline, result));
            } else {
                result.completeExceptionally(cause);
            }
        });
    }
}
