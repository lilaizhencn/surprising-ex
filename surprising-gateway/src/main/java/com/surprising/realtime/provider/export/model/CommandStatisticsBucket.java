package com.surprising.realtime.provider.export.model;
import com.surprising.aeron.protocol.*;
public record CommandStatisticsBucket(long minute,long user,CoreMessageType type,ResponseStatus status,CoreResultCode code) {}
