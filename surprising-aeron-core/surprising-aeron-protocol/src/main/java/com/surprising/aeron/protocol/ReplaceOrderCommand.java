package com.surprising.aeron.protocol;

public final class ReplaceOrderCommand {
    private long originalOrderId;
    private PlaceOrderCommand replacement;
    private final boolean commandSlot;

    public ReplaceOrderCommand(long originalOrderId, PlaceOrderCommand replacement) {
        commandSlot = false;
        assign(originalOrderId, replacement == null ? null : replacement.immutableValue());
    }
    private ReplaceOrderCommand() {
        commandSlot = true;
        replacement = PlaceOrderCommand.commandSlot();
    }
    public static ReplaceOrderCommand commandSlot() { return new ReplaceOrderCommand(); }
    public long originalOrderId() { return originalOrderId; }
    public PlaceOrderCommand replacement() { return replacement; }
    private void assign(long originalOrderId, PlaceOrderCommand replacement) {
        if (originalOrderId <= 0 || replacement == null || replacement.orderId() == originalOrderId)
            throw new IllegalArgumentException("invalid replace order command");
        this.originalOrderId = originalOrderId;
        this.replacement = replacement;
    }
    void decodedOriginalOrderId(long originalOrderId) {
        if (!commandSlot) throw new IllegalStateException("cannot decode into an immutable replace command");
        assign(originalOrderId, replacement);
    }
    public void clearCommandSlot() {
        if (!commandSlot) throw new IllegalStateException("cannot clear an immutable replace command");
        originalOrderId = 0;
        replacement.clearCommandSlot();
    }
    @Override public boolean equals(Object other) {
        return other instanceof ReplaceOrderCommand value && originalOrderId == value.originalOrderId
                && replacement.equals(value.replacement);
    }
    @Override public int hashCode() { return 31 * Long.hashCode(originalOrderId) + replacement.hashCode(); }
    @Override public String toString() {
        return "ReplaceOrderCommand[originalOrderId=" + originalOrderId + ", replacement=" + replacement + "]";
    }
}
