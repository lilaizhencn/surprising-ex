package com.surprising.product.api;

public final class ProductLineSql {

    private ProductLineSql() {
    }

    public static String contractTypeProductLineCase(String contractTypeExpression) {
        String expression = contractTypeExpression == null || contractTypeExpression.isBlank()
                ? "contract_type"
                : contractTypeExpression.trim();
        return """
                CASE %s
                    WHEN 'SPOT' THEN 'SPOT'
                    WHEN 'LINEAR_PERPETUAL' THEN 'LINEAR_PERPETUAL'
                    WHEN 'INVERSE_PERPETUAL' THEN 'INVERSE_PERPETUAL'
                    WHEN 'LINEAR_DELIVERY' THEN 'LINEAR_DELIVERY'
                    WHEN 'INVERSE_DELIVERY' THEN 'INVERSE_DELIVERY'
                    WHEN 'VANILLA_OPTION' THEN 'OPTION'
                    ELSE 'LINEAR_PERPETUAL'
                END
                """.formatted(expression).strip();
    }
}
