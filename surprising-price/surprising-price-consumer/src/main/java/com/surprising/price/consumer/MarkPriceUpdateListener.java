package com.surprising.price.consumer;

import com.surprising.price.api.model.MarkPriceEvent;

/** Receives every accepted mark-price update after the local latest-price cache has been updated. */
@FunctionalInterface
public interface MarkPriceUpdateListener {
    void onMarkPriceUpdated(MarkPriceEvent previous, MarkPriceEvent current);
}
