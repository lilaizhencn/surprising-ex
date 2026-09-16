package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClusterCommandWindowRoutingTest {

    @Test
    void differentUsersSharingALaneCanFillTheOwnerWindow() {
        var window = new ClusterCommandWindow(64);

        window.resetCandidate(101);
        window.route(0, 1L);
        window.add(null, null, 0, 0);

        window.resetCandidate(202);
        window.route(0, 1L);
        assertThat(window.conflictingPrefixSize()).isZero();

        window.resetCandidate(101);
        window.route(0, 1L);
        assertThat(window.conflictingPrefixSize()).isOne();
    }
}
