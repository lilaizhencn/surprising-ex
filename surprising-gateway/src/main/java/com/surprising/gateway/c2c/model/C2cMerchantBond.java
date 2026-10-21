package com.surprising.gateway.c2c.model;

import java.util.UUID;

public record C2cMerchantBond(UUID id,long userId,UUID requestId,String asset,long amountUnits,String status,long assetScale) { }
