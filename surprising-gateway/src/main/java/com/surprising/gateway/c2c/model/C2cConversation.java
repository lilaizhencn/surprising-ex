package com.surprising.gateway.c2c.model;

import java.util.UUID;

public record C2cConversation(UUID id,long low,long high,UUID orderId) {
    public long peer(long user) {return user==low?high:low;}
}
