package com.surprising.trading.trigger.model;

public record TriggerPosition(
        long signedQuantitySteps,
        long instrumentVersion) {
}
