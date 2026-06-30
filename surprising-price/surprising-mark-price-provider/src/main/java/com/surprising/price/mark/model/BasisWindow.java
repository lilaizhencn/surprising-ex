package com.surprising.price.mark.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

public class BasisWindow {

    private final Deque<BasisSample> samples = new ArrayDeque<>();

    public synchronized void add(Instant now, BigDecimal basis, Duration window) {
        samples.addLast(new BasisSample(now, basis));
        evict(now, window);
    }

    public synchronized BigDecimal average(Instant now, Duration window, int scale) {
        evict(now, window);
        if (samples.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = samples.stream()
                .map(BasisSample::basis)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(samples.size()), scale, RoundingMode.HALF_UP);
    }

    private void evict(Instant now, Duration window) {
        Instant cutoff = now.minus(window);
        while (!samples.isEmpty() && samples.peekFirst().time().isBefore(cutoff)) {
            samples.removeFirst();
        }
    }

    private record BasisSample(Instant time, BigDecimal basis) {
    }
}
