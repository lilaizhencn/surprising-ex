package com.surprising.aeron.service.command.order;

import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import com.surprising.aeron.service.state.admission.ResolvedPlaceOrder;

public record ResolvedMatchingAdmission(long userId, long originalOrderId, long originalOrderRevision,
                                 long userRevision, PlaceOrderCommand command,
                                 ResolvedPlaceOrder resolved, CoreMatchingOrder matchingOrder,
                                 long requiredReservationUnits) {

    public ResolvedMatchingAdmission {
        if (userId <= 0 || originalOrderId <= 0 || originalOrderRevision < 0 || userRevision < 0
                || command == null || resolved == null
                || requiredReservationUnits < 0 || command.orderId() != resolved.orderId()
                || (matchingOrder != null && matchingOrder.orderId() != resolved.orderId())) {
            throw new IllegalArgumentException("invalid resolved matching admission");
        }
    }

    public ResolvedMatchingAdmission(long userId, long originalOrderId, long originalOrderRevision,
                                     long userRevision, PlaceOrderCommand command,
                                     ResolvedPlaceOrder resolved,
                                     long requiredReservationUnits) {
        this(userId, originalOrderId, originalOrderRevision, userRevision, command, resolved, null, requiredReservationUnits);
    }
}
