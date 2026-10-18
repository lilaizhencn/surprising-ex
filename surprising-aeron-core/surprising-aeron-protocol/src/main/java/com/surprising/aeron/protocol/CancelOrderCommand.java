package com.surprising.aeron.protocol;

/** Immutable public input, or one decoder-owned order-ID slot held until command retirement. */
public final class CancelOrderCommand {
    private long orderId;
    private final boolean commandSlot;

    public CancelOrderCommand(long orderId) {
        commandSlot = false;
        assign(orderId);
    }

    private CancelOrderCommand() { commandSlot = true; }
    public static CancelOrderCommand commandSlot() { return new CancelOrderCommand(); }
    public long orderId() { return orderId; }

    private void assign(long orderId) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        this.orderId = orderId;
    }

    void decodeInto(long orderId) {
        if (!commandSlot) throw new IllegalStateException("cannot decode into an immutable cancel command");
        assign(orderId);
    }

    public void clearCommandSlot() {
        if (!commandSlot) throw new IllegalStateException("cannot clear an immutable cancel command");
        orderId = 0;
    }

    CancelOrderCommand immutableValue() { return commandSlot ? new CancelOrderCommand(orderId) : this; }
    boolean isCommandSlot() { return commandSlot; }
    @Override public boolean equals(Object other) {
        return other instanceof CancelOrderCommand value && orderId == value.orderId;
    }
    @Override public int hashCode() { return Long.hashCode(orderId); }
    @Override public String toString() { return "CancelOrderCommand[orderId=" + orderId + "]"; }
}
