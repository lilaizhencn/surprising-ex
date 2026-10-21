package com.surprising.websocket.provider.model;

import java.time.Instant;

public record TimedPayload(Object payload, Instant eventTime) {

}
