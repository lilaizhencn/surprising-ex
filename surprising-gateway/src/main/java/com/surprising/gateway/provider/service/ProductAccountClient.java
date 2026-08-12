package com.surprising.gateway.provider.service;

public interface ProductAccountClient {

    ProductAccountAdjustment adjust(String accountType,
                                    long amountUnits,
                                    String referenceId,
                                    String reason,
                                    long userId,
                                    String asset);
}
