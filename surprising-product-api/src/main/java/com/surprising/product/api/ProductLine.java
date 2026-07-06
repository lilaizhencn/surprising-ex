package com.surprising.product.api;

import java.util.Locale;
import java.util.Optional;

public enum ProductLine {
    SPOT("spot", "SPOT", false, false, false, false),
    LINEAR_PERPETUAL("linear-perp", "USDT_PERPETUAL", true, true, false, false),
    INVERSE_PERPETUAL("inverse-perp", "COIN_PERPETUAL", true, true, false, false),
    LINEAR_DELIVERY("linear-delivery", "USDT_DELIVERY", true, false, true, false),
    INVERSE_DELIVERY("inverse-delivery", "COIN_DELIVERY", true, false, true, false),
    OPTION("option", "OPTION", true, false, true, true);

    private final String topicSegment;
    private final String accountTypeCode;
    private final boolean marginProduct;
    private final boolean fundingProduct;
    private final boolean deliveryProduct;
    private final boolean optionProduct;

    ProductLine(String topicSegment,
                String accountTypeCode,
                boolean marginProduct,
                boolean fundingProduct,
                boolean deliveryProduct,
                boolean optionProduct) {
        this.topicSegment = topicSegment;
        this.accountTypeCode = accountTypeCode;
        this.marginProduct = marginProduct;
        this.fundingProduct = fundingProduct;
        this.deliveryProduct = deliveryProduct;
        this.optionProduct = optionProduct;
    }

    public String topicSegment() {
        return topicSegment;
    }

    public String accountTypeCode() {
        return accountTypeCode;
    }

    public String contractTypeCode() {
        return switch (this) {
            case SPOT -> "SPOT";
            case LINEAR_PERPETUAL -> "LINEAR_PERPETUAL";
            case INVERSE_PERPETUAL -> "INVERSE_PERPETUAL";
            case LINEAR_DELIVERY -> "LINEAR_DELIVERY";
            case INVERSE_DELIVERY -> "INVERSE_DELIVERY";
            case OPTION -> "VANILLA_OPTION";
        };
    }

    public boolean isDerivative() {
        return this != SPOT;
    }

    public boolean isMarginProduct() {
        return marginProduct;
    }

    public boolean isFundingProduct() {
        return fundingProduct;
    }

    public boolean isDeliveryProduct() {
        return deliveryProduct;
    }

    public boolean isOptionProduct() {
        return optionProduct;
    }

    public static Optional<ProductLine> fromAccountTypeCode(String accountTypeCode) {
        String normalized = normalize(accountTypeCode);
        for (ProductLine productLine : values()) {
            if (productLine.accountTypeCode.equals(normalized)) {
                return Optional.of(productLine);
            }
        }
        return Optional.empty();
    }

    public static Optional<ProductLine> fromContractTypeCode(String contractTypeCode) {
        String normalized = normalize(contractTypeCode);
        for (ProductLine productLine : values()) {
            if (productLine.contractTypeCode().equals(normalized)) {
                return Optional.of(productLine);
            }
        }
        return Optional.empty();
    }

    public static ProductLine requireAccountTypeCode(String accountTypeCode) {
        return fromAccountTypeCode(accountTypeCode)
                .orElseThrow(() -> new IllegalArgumentException("unsupported product account type: " + accountTypeCode));
    }

    public static ProductLine requireContractTypeCode(String contractTypeCode) {
        return fromContractTypeCode(contractTypeCode)
                .orElseThrow(() -> new IllegalArgumentException("unsupported product contract type: " + contractTypeCode));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
