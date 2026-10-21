package com.surprising.realtime.provider.option.model;

import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.provider.option.utils.Black76Reference;
import java.math.BigDecimal;
import java.time.Instant;

/** 期权只读参考报价；不作为资金或风险准入依据。 */
public record OptionQuote(String productLine, String instrumentId, String underlyingInstrumentId, ProductLine underlyingProductLine,
                        OptionType optionType, Instant expiryTime, String strikePriceUnits, BigDecimal strikePrice,
                        String baseAsset, String quoteAsset, BigDecimal premiumPrice, BigDecimal underlyingIndexPrice,
                        BigDecimal sameExpiryForwardPrice, BigDecimal intrinsicValue, Instant asOf, Instant validUntil,
                        String sourcePriceSequence, String model, BigDecimal discountFactor, String unit, Black76Reference.Greeks greeks) {}
