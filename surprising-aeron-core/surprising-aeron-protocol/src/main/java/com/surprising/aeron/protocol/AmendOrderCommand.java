package com.surprising.aeron.protocol;

public record AmendOrderCommand(
        long originalOrderId,
        long replacementOrderId,
        String newClientOrderId,
        long rawPriceTicks,
        long rawQuantitySteps,
        CoreTimeInForce timeInForce,
        byte rawPostOnly) {

    public static final long UNCHANGED_PRICE = 0L;
    public static final long UNCHANGED_QUANTITY = 0L;
    public static final byte UNCHANGED_POST_ONLY = -1;

    public AmendOrderCommand {
        if (originalOrderId <= 0 || replacementOrderId <= 0 || originalOrderId == replacementOrderId
                || (rawPriceTicks != 0 && rawPriceTicks <= 0)
                || (rawQuantitySteps != 0 && rawQuantitySteps <= 0)
                || (newClientOrderId != null && newClientOrderId.length() > 64)
                || timeInForce == CoreTimeInForce.IOC || timeInForce == CoreTimeInForce.FOK
                || (rawPriceTicks == 0 && rawQuantitySteps == 0 && timeInForce == null
                        && rawPostOnly == UNCHANGED_POST_ONLY && newClientOrderId == null)) {
            throw new IllegalArgumentException("invalid amend order command");
        }
    }

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
}
