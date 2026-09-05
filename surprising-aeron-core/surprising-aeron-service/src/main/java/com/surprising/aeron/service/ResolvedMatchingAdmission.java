package com.surprising.aeron.service;

import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.matching.CoreMatchingOrder;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;

record ResolvedMatchingAdmission(long userId, long originalOrderId, long originalOrderRevision,
                                 long userRevision, PlaceOrderCommand command,
                                 ResolvedPlaceOrder resolved, CoreMatchingOrder matchingOrder,
                                 long requiredReservationUnits) {

    ResolvedMatchingAdmission {
        if (userId <= 0 || originalOrderId <= 0 || originalOrderRevision < 0 || userRevision < 0
                || command == null || resolved == null || matchingOrder == null
                || requiredReservationUnits < 0 || command.orderId() != resolved.orderId()
                || matchingOrder.orderId() != resolved.orderId()) {
            throw new IllegalArgumentException("invalid resolved matching admission");
        }
    }
}
