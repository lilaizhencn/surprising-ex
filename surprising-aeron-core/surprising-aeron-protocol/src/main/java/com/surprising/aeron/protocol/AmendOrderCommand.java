package com.surprising.aeron.protocol;

/** Public values are immutable; decoder slots belong to one active command. */
public final class AmendOrderCommand {
    private long originalOrderId;
    private long replacementOrderId;
    private String newClientOrderId;
    private long rawPriceTicks;
    private long rawQuantitySteps;
    private CoreTimeInForce timeInForce;
    private byte rawPostOnly;
    private final boolean commandSlot;

    private static final long UNCHANGED_PRICE = 0L;
    private static final long UNCHANGED_QUANTITY = 0L;
    public static final byte UNCHANGED_POST_ONLY = -1;

    public AmendOrderCommand(long originalOrderId, long replacementOrderId, String newClientOrderId, long rawPriceTicks, long rawQuantitySteps, CoreTimeInForce timeInForce, byte rawPostOnly) {
        commandSlot = false;
        assign(originalOrderId, replacementOrderId, newClientOrderId, rawPriceTicks, rawQuantitySteps, timeInForce, rawPostOnly);
    }

    private AmendOrderCommand() { commandSlot = true; }
    public static AmendOrderCommand commandSlot() { return new AmendOrderCommand(); }
    public long originalOrderId() { return originalOrderId; }
    public long replacementOrderId() { return replacementOrderId; }
    public String newClientOrderId() { return newClientOrderId; }
    public long rawPriceTicks() { return rawPriceTicks; }
    public long rawQuantitySteps() { return rawQuantitySteps; }
    public CoreTimeInForce timeInForce() { return timeInForce; }
    public byte rawPostOnly() { return rawPostOnly; }

    private void assign(long originalOrderId, long replacementOrderId, String newClientOrderId, long rawPriceTicks, long rawQuantitySteps, CoreTimeInForce timeInForce, byte rawPostOnly) {
        if (originalOrderId <= 0 || replacementOrderId <= 0 || originalOrderId == replacementOrderId
                || (rawPriceTicks != 0 && rawPriceTicks <= 0)
                || (rawQuantitySteps != 0 && rawQuantitySteps <= 0)
                || (newClientOrderId != null && newClientOrderId.length() > 64)
                || rawPostOnly < UNCHANGED_POST_ONLY || rawPostOnly > 1
                || timeInForce == CoreTimeInForce.IOC || timeInForce == CoreTimeInForce.FOK
                || (rawPriceTicks == 0 && rawQuantitySteps == 0 && timeInForce == null
                        && rawPostOnly == UNCHANGED_POST_ONLY && newClientOrderId == null)) {
            throw new IllegalArgumentException("invalid amend order command");
        }
        this.originalOrderId = originalOrderId;
        this.replacementOrderId = replacementOrderId;
        this.newClientOrderId = newClientOrderId;
        this.rawPriceTicks = rawPriceTicks;
        this.rawQuantitySteps = rawQuantitySteps;
        this.timeInForce = timeInForce;
        this.rawPostOnly = rawPostOnly;
    }

    void decodeInto(long originalOrderId, long replacementOrderId, String newClientOrderId, long rawPriceTicks, long rawQuantitySteps, CoreTimeInForce timeInForce, byte rawPostOnly) {
        if (!commandSlot) throw new IllegalStateException("cannot decode into an immutable amend command");
        assign(originalOrderId, replacementOrderId, newClientOrderId, rawPriceTicks, rawQuantitySteps, timeInForce, rawPostOnly);
    }
    public void clearCommandSlot() {
        if (!commandSlot) throw new IllegalStateException("cannot clear an immutable amend command");
        originalOrderId = replacementOrderId = rawPriceTicks = rawQuantitySteps = 0;
        newClientOrderId = null;
        timeInForce = null;
        rawPostOnly = UNCHANGED_POST_ONLY;
    }
    boolean isCommandSlot() { return commandSlot; }
    AmendOrderCommand immutableValue() { return commandSlot ? new AmendOrderCommand(originalOrderId, replacementOrderId, newClientOrderId, rawPriceTicks, rawQuantitySteps, timeInForce, rawPostOnly) : this; }

    public AmendOrderCommand(
            long originalOrderId,
            long replacementOrderId,
            String newClientOrderId,
            Long priceTicks,
            Long quantitySteps,
            CoreTimeInForce timeInForce,
            Boolean postOnly) {
        this(originalOrderId, replacementOrderId, newClientOrderId,
                priceTicks == null ? UNCHANGED_PRICE : priceTicks,
                quantitySteps == null ? UNCHANGED_QUANTITY : quantitySteps,
                timeInForce,
                postOnly == null ? UNCHANGED_POST_ONLY : (byte) (postOnly ? 1 : 0));
    }

    public boolean hasPriceTicks() { return rawPriceTicks > 0; }
    public boolean hasQuantitySteps() { return rawQuantitySteps > 0; }
    public boolean hasPostOnly() { return rawPostOnly >= 0; }
    public boolean isPostOnly() { return rawPostOnly == 1; }

    public Long priceTicks() { return rawPriceTicks > 0 ? rawPriceTicks : null; }
    public Long quantitySteps() { return rawQuantitySteps > 0 ? rawQuantitySteps : null; }
    public Boolean postOnly() { return rawPostOnly >= 0 ? rawPostOnly == 1 : null; }
    @Override public boolean equals(Object other) {
        return other instanceof AmendOrderCommand value
                && originalOrderId == value.originalOrderId && replacementOrderId == value.replacementOrderId
                && rawPriceTicks == value.rawPriceTicks && rawQuantitySteps == value.rawQuantitySteps
                && rawPostOnly == value.rawPostOnly && timeInForce == value.timeInForce
                && java.util.Objects.equals(newClientOrderId, value.newClientOrderId);
    }
    @Override public int hashCode() {
        return java.util.Objects.hash(originalOrderId, replacementOrderId, newClientOrderId,
                rawPriceTicks, rawQuantitySteps, timeInForce, rawPostOnly);
    }
    @Override public String toString() {
        return "AmendOrderCommand[originalOrderId=" + originalOrderId + ", replacementOrderId="
                + replacementOrderId + ", newClientOrderId=" + newClientOrderId + ", rawPriceTicks="
                + rawPriceTicks + ", rawQuantitySteps=" + rawQuantitySteps + ", timeInForce="
                + timeInForce + ", rawPostOnly=" + rawPostOnly + "]";
    }
}
