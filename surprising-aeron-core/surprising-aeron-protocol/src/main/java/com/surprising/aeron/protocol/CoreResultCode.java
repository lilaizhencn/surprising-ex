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
    INVALID_COMMAND(21),
    INSUFFICIENT_LOCKED_BALANCE(22),
    INSUFFICIENT_ORDER_RESERVATION(23),
    DERIVATIVE_CLOSE_REQUIRES_PNL_MODEL(24),
    INVALID_MARGIN_ALLOCATION(25),
    MATCHING_REJECTED(26),
    INVALID_REPLACEMENT_RESERVATION(27),
    INSTRUMENT_VERSION_OPEN_BOOK_MISMATCH(28),
    SELF_TRADE_PREVENTED(29),
    OPTION_MATCH_REQUIRES_PREMIUM_MODEL(30),
    INVALID_CONTRACT_TYPE(31),
    STALE_INSTRUMENT_VERSION(32),
    INSTRUMENT_VERSION_IN_USE(33),
    INSTRUMENT_NOT_FOUND(34),
    INSTRUMENT_VERSION_CONFLICT(35),
    STALE_MARK_PRICE(36),
    INVALID_OPTION_TYPE(37),
    INSTRUMENT_ORDER_MISMATCH(38),
    INVALID_ORDER_PRICE(39),
    REDUCE_ONLY_UNSUPPORTED(40),
    REDUCE_ONLY_CAPACITY_EXCEEDED(41),
    PRODUCT_LINE_UNSUPPORTED(42),
    MARK_PRICE_NOT_FOUND(43),
    INVALID_SETTLEMENT_PRICE(44),
    STALE_SETTLEMENT_ID(45),
    LIQUIDATION_NOT_FOUND(46),
    LIQUIDATION_STATE_CONFLICT(47),
    POSITION_NOT_FOUND(48),
    INSURANCE_COVER_EXCEEDS_DEFICIT(49),
    INSTRUMENT_SETTLED(50),
    INSURANCE_COVER_MISMATCH(51),
    EXPORT_BACKLOG_FULL(60),
    EXPORT_ACK_AHEAD(61);

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
