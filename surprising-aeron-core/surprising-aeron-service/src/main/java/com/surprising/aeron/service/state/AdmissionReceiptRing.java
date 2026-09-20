package com.surprising.aeron.service.state;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * One Lane to Matcher admission mailbox.  A lane is the only producer and the
 * matcher shard is the only consumer, so the mailbox needs no CAS or node
 * allocation. The sequence array publishes both primitive metadata and the
 * admission outcome. Matcher input already lives in the pooled settlement event,
 * so the receipt does not duplicate the Lane-owned runtime order.
 */
final class AdmissionReceiptRing {
    private static final int SPIN_LIMIT = 1_024;
    private static final long PARK_NANOS = 1_000L;
    private static final long WAIT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final VarHandle SEQUENCES = MethodHandles.arrayElementVarHandle(long[].class);

    private final long[] sequences;
    private final long[] reservationIds;
    private final long[] accountVersions;
    private final long[] reservedAmounts;
    private final int[] resultCodes;
    private final byte[] accepted;
    private final int mask;
    private volatile long producerPosition;
    private volatile long consumerPosition;

    AdmissionReceiptRing(int requestedCapacity) {
        int capacity = 1;
        while (capacity < requestedCapacity) capacity = Math.multiplyExact(capacity, 2);
        sequences = new long[capacity];
        reservationIds = new long[capacity];
        accountVersions = new long[capacity];
        reservedAmounts = new long[capacity];
        resultCodes = new int[capacity];
        accepted = new byte[capacity];
        mask = capacity - 1;
    }

    boolean hasCapacity() {
        return producerPosition - consumerPosition < sequences.length;
    }

    void publish(long coreSequence, long reservationId, long accountVersion,
                 long reservedAmount, boolean accepted, int resultCode) {
        if (coreSequence <= 0 || reservationId <= 0 || accountVersion < 0
                || reservedAmount < 0 || resultCode < 0) {
            throw new IllegalArgumentException("invalid admission receipt");
        }
        long position = producerPosition;
        if (position - consumerPosition >= sequences.length) {
            throw new IllegalStateException("admission receipt ring is full");
        }
        int index = (int) position & mask;
        if ((long) SEQUENCES.getAcquire(sequences, index) != 0) {
            throw new IllegalStateException("admission receipt slot was not released");
        }
        reservationIds[index] = reservationId;
        accountVersions[index] = accountVersion;
        reservedAmounts[index] = reservedAmount;
        this.accepted[index] = (byte) (accepted ? 1 : 0);
        resultCodes[index] = resultCode;
        SEQUENCES.setRelease(sequences, index, coreSequence);
        producerPosition = position + 1;
    }

    /**
     * Waits for the receipt at the expected command sequence and copies it
     * directly into the already pooled matcher event.  No receipt object is
     * allocated and an out-of-order publication is treated as a deterministic
     * pipeline failure rather than silently consuming another command's result.
     */
    void await(long expectedCoreSequence, MatcherSettlementEvent target) {
        if (expectedCoreSequence <= 0 || target == null) {
            throw new IllegalArgumentException("invalid admission receipt wait");
        }
        long deadline = System.nanoTime() + WAIT_TIMEOUT_NANOS;
        int spins = 0;
        while (true) {
            long position = consumerPosition;
            if (position < producerPosition) {
                int index = (int) position & mask;
                long published = (long) SEQUENCES.getAcquire(sequences, index);
                if (published == expectedCoreSequence) {
                    target.admissionReceipt(
                            reservationIds[index], accountVersions[index], reservedAmounts[index],
                            accepted[index] != 0, resultCodes[index]);
                    SEQUENCES.setRelease(sequences, index, 0L);
                    consumerPosition = position + 1;
                    return;
                }
                if (published > expectedCoreSequence) {
                    throw new IllegalStateException("admission receipt sequence advanced past matcher command");
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("timed out waiting for admission receipt " + expectedCoreSequence);
            }
            if (spins++ < SPIN_LIMIT) Thread.onSpinWait();
            else {
                LockSupport.parkNanos(this, PARK_NANOS);
                spins = 0;
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("admission receipt wait was interrupted");
                }
            }
        }
    }
}
