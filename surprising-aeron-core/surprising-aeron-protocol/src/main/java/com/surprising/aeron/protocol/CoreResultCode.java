package com.surprising.aeron.protocol;

import java.util.Arrays;

public enum CoreResultCode {
    NONE(0),
    PRODUCT_LINE_MISMATCH(1),
    INVALID_MESSAGE(2),
    STALE_SOURCE_SEQUENCE(3),
    INSUFFICIENT_AVAILABLE_BALANCE(10),
    DUPLICATE_ORDER_ID(11),
    REDUCE_ONLY_REQUIRES_POSITION_STATE(12),
    INVALID_RESERVATION_KIND(13),
    INVALID_DERIVATIVE_RESERVATION_ASSET(14),
    INVALID_SPOT_RESERVATION_ASSET(15),
    INVALID_USER_ID(16),
    ORDER_NOT_FOUND(17),
    ORDER_OWNER_MISMATCH(18),
    ARITHMETIC_OVERFLOW(19),
    ENTITY_NOT_FOUND(20),
    INVALID_COMMAND(21);

    private final int wireCode;

    CoreResultCode(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    public static CoreResultCode fromWireCode(int wireCode) {
        return Arrays.stream(values()).filter(value -> value.wireCode == wireCode).findFirst()
                .orElseThrow(() -> new ProtocolException("unsupported result code: " + wireCode));
    }

    public static CoreResultCode fromRejectionCode(String code) {
        try {
            return valueOf(code);
        } catch (IllegalArgumentException exception) {
            return INVALID_COMMAND;
        }
    }
}
