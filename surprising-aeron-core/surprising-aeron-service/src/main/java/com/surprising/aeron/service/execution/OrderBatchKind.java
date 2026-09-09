package com.surprising.aeron.service.execution;

/** Owner-confined batch execution state; reused only after terminal commit. */
enum OrderBatchKind {
    PLACE,
    CANCEL,
    AMEND
}
