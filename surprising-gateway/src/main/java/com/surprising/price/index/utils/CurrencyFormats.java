package com.surprising.price.index.utils;

import java.util.Locale;

/** HTTP币种代码及后台供应商配置共用的纯格式工具。 */
public final class CurrencyFormats {
    private CurrencyFormats() { }

    public static String normalize(String currency) {
        if (currency == null || !currency.matches("[A-Za-z]{2,10}")) {
            throw new IllegalArgumentException("invalid currency: " + currency);
        }
        return currency.toUpperCase(Locale.ROOT);
    }
}
