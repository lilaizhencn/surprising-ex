package com.surprising.gateway.c2c.model;

import java.util.UUID;
import java.time.Instant;

public record C2cMessage(long id,UUID conversation,Long sender,UUID request,String text,UUID attachment,String kind,Instant createdAt) {}
