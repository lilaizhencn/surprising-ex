package com.surprising.gateway.c2c.model;

import java.util.UUID;

public record C2cAttachment(UUID id,long user,UUID order,UUID conversation,String purpose,String key,String mime,int bytes,String sha256,String name,String status) {}
