package com.surprising.trading.trigger.service;

import com.surprising.trading.api.model.TriggerOrderBatchResponse;

public class AtomicTriggerBatchRejectedException extends RuntimeException {

    private final TriggerOrderBatchResponse response;

    public AtomicTriggerBatchRejectedException(TriggerOrderBatchResponse response, Throwable cause) {
        super(cause.getMessage(), cause);
        this.response = response;
    }

    public TriggerOrderBatchResponse response() {
        return response;
    }
}
