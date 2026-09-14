package com.surprising.aeron.service.command;

/** Owner-confined batch execution state; reused only after terminal commit. */
public enum OrderBatchKind {
    PLACE,
    CANCEL,
    AMEND
}
