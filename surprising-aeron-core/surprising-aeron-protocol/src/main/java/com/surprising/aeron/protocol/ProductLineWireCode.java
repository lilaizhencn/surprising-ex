package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.util.EnumMap;
import java.util.Map;

public final class ProductLineWireCode {

    private static final Map<ProductLine, Integer> CODES = new EnumMap<>(ProductLine.class);

    static {
        CODES.put(ProductLine.SPOT, 1);
        CODES.put(ProductLine.LINEAR_PERPETUAL, 2);
        CODES.put(ProductLine.INVERSE_PERPETUAL, 3);
        CODES.put(ProductLine.LINEAR_DELIVERY, 4);
        CODES.put(ProductLine.INVERSE_DELIVERY, 5);
        CODES.put(ProductLine.OPTION, 6);
    }

    private ProductLineWireCode() {
    }

    public static int encode(ProductLine productLine) {
        Integer code = CODES.get(productLine);
        if (code == null) {
            throw new ProtocolException("unsupported product line: " + productLine);
        }
        return code;
    }

    public static ProductLine decode(int wireCode) {
        return CODES.entrySet().stream()
                .filter(entry -> entry.getValue() == wireCode)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new ProtocolException("unsupported product line code: " + wireCode));
    }
}
