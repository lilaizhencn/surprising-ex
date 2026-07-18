package com.surprising.account.provider.model;

import com.surprising.account.api.model.AccountCommandStatus;

public record AccountCommandTerminalResult(
        AccountCommandStatus status,
        String resultPayload,
        String errorCode,
        String errorMessage) {
}
