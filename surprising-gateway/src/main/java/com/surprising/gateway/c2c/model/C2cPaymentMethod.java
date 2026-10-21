package com.surprising.gateway.c2c.model;

import java.util.List;

public record C2cPaymentMethod(String code, String label, boolean enabled, List<String> currencies,
                               boolean requiresBankName, boolean supportsQr, long revision) { }
