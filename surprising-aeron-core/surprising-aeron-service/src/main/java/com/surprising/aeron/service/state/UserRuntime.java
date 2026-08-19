package com.surprising.aeron.service.state;

public record UserRuntime(long userId) {
    public UserRuntime {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
    }
}
