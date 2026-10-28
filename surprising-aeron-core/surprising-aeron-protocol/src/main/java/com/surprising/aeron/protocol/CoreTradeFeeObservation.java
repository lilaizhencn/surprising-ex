package com.surprising.aeron.protocol;

/** Read-only committed replay observation; no command/wire/snapshot changes. */
public record CoreTradeFeeObservation(
  long orderId,
  long userId,
  String instrumentId,
  String asset,
  long cumulativeFeeUnits,
  long clusterPosition,
  long occurredAt
) {}
