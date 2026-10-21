package com.surprising.gateway.c2c.model;

import java.util.UUID;

public record C2cBondDeposit(UUID requestId,long policyRevision,String emailCode,String totpCode) { }
