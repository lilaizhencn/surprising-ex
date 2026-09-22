package com.surprising.trading.api.model;

import java.util.List;

public record AdminTriggerOrderTimelineResponse(
        TriggerOrderResponse order,
        List<AdminTriggerOrderTimelineEvent> events) {
}
