package com.surprising.aeron.client;

import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.WireMessageKind;
import java.util.Objects;

public enum CoreQueryClass {
    RESERVED_CONTROL,
    ORDINARY_READ;

    public static CoreQueryClass classify(CoreMessageType type) {
        Objects.requireNonNull(type, "type");
        if (type.kind() != WireMessageKind.QUERY) {
            throw new IllegalArgumentException("query message type is required");
        }
        return switch (type) {
            case COMMAND_RESULT_QUERY, ORDER_PREFLIGHT_QUERY,
                    STATE_HASH_QUERY, BUSINESS_STATE_HASH_QUERY, USER_STATE_HASH_QUERY, ORDER_STATE_HASH_QUERY,
                    EXPORT_BATCH_QUERY, EXPORT_STATUS_QUERY, TREASURY_STATE_QUERY, ADL_CANDIDATE_QUERY,
                    RISK_STATE_QUERY, OPEN_INTEREST_QUERY, CANCEL_ALL_AFTER_QUERY, LIQUIDATION_WORK_QUERY,
                    FUNDING_PROGRESS_QUERY, SETTLEMENT_PROGRESS_QUERY, BOOK_STATE_QUERY,
                    ORDER_BOOK_BOOTSTRAP_QUERY -> RESERVED_CONTROL;
            default -> ORDINARY_READ;
        };
    }
}
