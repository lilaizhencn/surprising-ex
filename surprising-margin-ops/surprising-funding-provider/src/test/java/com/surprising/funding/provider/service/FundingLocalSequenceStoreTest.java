package com.surprising.funding.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class FundingLocalSequenceStoreTest {

    @Test
    void sequenceSurvivesRestartAndRemainsIndependentPerSymbol() throws Exception {
        var directory = Files.createTempDirectory("funding-sequence-test");
        try (FundingLocalSequenceStore store = new FundingLocalSequenceStore(directory)) {
            assertThat(store.next("btc-usdt")).isEqualTo(1L);
            assertThat(store.next("BTC-USDT")).isEqualTo(2L);
            assertThat(store.next("ETH-USDT")).isEqualTo(1L);
        }
        try (FundingLocalSequenceStore store = new FundingLocalSequenceStore(directory)) {
            assertThat(store.current("BTC-USDT")).isEqualTo(2L);
            assertThat(store.next("BTC-USDT")).isEqualTo(3L);
            assertThat(store.current("ETH-USDT")).isEqualTo(1L);
        }
    }
}
