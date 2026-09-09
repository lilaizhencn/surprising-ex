package com.surprising.aeron.service.execution;

import com.surprising.aeron.service.state.RuntimeIdentityRegistry;

/** Owner-confined batch execution state; reused only after terminal commit. */
record PreparedClientAllocation(
        long userId, String clientOrderId,
        com.surprising.aeron.service.state.RuntimeIdentityRegistry.PreparedClientKey prepared) {}
