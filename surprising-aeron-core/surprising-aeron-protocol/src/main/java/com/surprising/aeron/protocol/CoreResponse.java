package com.surprising.aeron.protocol;

public record CoreResponse(ResponseStatus status, long appliedCommandCount, long stateHash) {
}
