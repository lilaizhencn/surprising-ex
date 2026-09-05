package com.surprising.aeron.protocol;

public record AmendOrderCommand(
        long originalOrderId,
        long replacementOrderId,
        String newClientOrderId,
        Long priceTicks,
        Long quantitySteps,
        CoreTimeInForce timeInForce,
        Boolean postOnly) {

    public AmendOrderCommand {
        if (originalOrderId <= 0 || replacementOrderId <= 0 || originalOrderId == replacementOrderId
                || priceTicks != null && priceTicks <= 0
                || quantitySteps != null && quantitySteps <= 0
                || newClientOrderId != null && newClientOrderId.length() > 64
                || timeInForce == CoreTimeInForce.IOC || timeInForce == CoreTimeInForce.FOK
                || priceTicks == null && quantitySteps == null && timeInForce == null
                        && postOnly == null && newClientOrderId == null) {
            throw new IllegalArgumentException("invalid amend order command");
        }
    }
}
