package com.surprising.aeron.tools;

enum HttpOutcome {
    SUCCESS_2XX,
    ACCEPTED_202,
    RATE_LIMITED_429,
    CLIENT_4XX,
    SERVER_5XX,
    TIMEOUT,
    TRANSPORT_ERROR,
    ORACLE_MISMATCH;

    static HttpOutcome fromStatus(int status) {
        if (status == 202) return ACCEPTED_202;
        if (status == 429) return RATE_LIMITED_429;
        if (status >= 200 && status < 300) return SUCCESS_2XX;
        if (status >= 400 && status < 500) return CLIENT_4XX;
        return SERVER_5XX;
    }
}
