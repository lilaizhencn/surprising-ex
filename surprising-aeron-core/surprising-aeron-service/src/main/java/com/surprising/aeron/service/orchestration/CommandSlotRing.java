package com.surprising.aeron.service.orchestration;

public final class CommandSlotRing {
    private final CommandSlot[] contexts;
    private final int mask;
    private int inFlight;
    private int highWaterMark;

    public CommandSlotRing(int capacity, int laneCount) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0
                || laneCount <= 0 || laneCount > Long.SIZE) {
            throw new IllegalArgumentException("lane command context capacity must be a power of two");
        }
        contexts = new CommandSlot[capacity];
        for (int index = 0; index < capacity; index++) contexts[index] = new CommandSlot();
        mask = capacity - 1;
    }

    CommandSlot claim(long coreSequence) {
        if (coreSequence <= 0) throw new IllegalArgumentException("coreSequence must be positive");
        CommandSlot context = contexts[(int) coreSequence & mask];
        if (context.claimed) throw new IllegalStateException("lane command context ring is full");
        context.coreSequence = coreSequence;
        context.claimed = true;
        inFlight++;
        highWaterMark = Math.max(highWaterMark, inFlight);
        return context;
    }

    public CommandSlot required(long coreSequence) {
        int slot = (int) coreSequence & mask;
        CommandSlot context = contexts[slot];
        if (!context.claimed || context.coreSequence != coreSequence) {
            throw new IllegalStateException("unknown lane command context sequence=" + coreSequence
                    + " slot=" + slot + " actual=" + context.coreSequence
                    + " inFlight=" + inFlight + " capacity=" + contexts.length);
        }
        return context;
    }

    CommandSlot contextAt(int slot) { return contexts[slot]; }

    void release(long coreSequence) {
        CommandSlot context = required(coreSequence);
        if (!context.complete() || context.submittedMatcherShard() != -1)
            throw new IllegalStateException("incomplete lane command context");
        context.clear();
        inFlight--;
    }

    public boolean claimed(long coreSequence) {
        CommandSlot slot = contexts[(int) coreSequence & mask];
        return slot.claimed && slot.coreSequence == coreSequence;
    }

    void discard(long coreSequence) {
        CommandSlot context = required(coreSequence);
        context.clear();
        inFlight--;
    }

    int inFlight() { return inFlight; }
    int highWaterMark() { return highWaterMark; }
    int capacity() { return contexts.length; }

}
