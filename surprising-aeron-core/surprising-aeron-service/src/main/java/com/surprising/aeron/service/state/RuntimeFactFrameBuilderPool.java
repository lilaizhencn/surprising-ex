package com.surprising.aeron.service.state;

import com.surprising.product.api.ProductLine;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded, preallocated pool; the builders themselves are the intrusive free-list nodes. */
final class RuntimeFactFrameBuilderPool {
    private volatile ProductLine productLine;
    private final AtomicReference<RuntimeFactFrame.Builder> free = new AtomicReference<>();
    private final int capacity;

    RuntimeFactFrameBuilderPool(ProductLine productLine, int capacity) {
        if (productLine == null || capacity <= 0) throw new IllegalArgumentException("invalid Fact Frame pool");
        this.productLine = productLine;
        this.capacity = capacity;
        RuntimeFactFrame.Builder head = null;
        for (int index = 0; index < capacity; index++) {
            RuntimeFactFrame.Builder builder = RuntimeFactFrame.builder(productLine);
            builder.nextFree(head);
            head = builder;
        }
        free.set(head);
    }

    RuntimeFactFrame.Builder acquire() {
        while (true) {
            RuntimeFactFrame.Builder head = free.get();
            if (head == null) throw new IllegalStateException("Fact Frame pool capacity is exhausted: " + capacity);
            RuntimeFactFrame.Builder next = head.nextFree();
            if (free.compareAndSet(head, next)) {
                head.nextFree(null);
                return head.reset(productLine);
            }
        }
    }

    void release(RuntimeFactFrame.Builder builder) {
        if (builder == null) throw new IllegalArgumentException("Fact Frame builder is required");
        builder.reset(productLine);
        while (true) {
            RuntimeFactFrame.Builder head = free.get();
            builder.nextFree(head);
            if (free.compareAndSet(head, builder)) return;
        }
    }

    void productLine(ProductLine productLine) {
        this.productLine = java.util.Objects.requireNonNull(productLine, "product line");
    }
}
