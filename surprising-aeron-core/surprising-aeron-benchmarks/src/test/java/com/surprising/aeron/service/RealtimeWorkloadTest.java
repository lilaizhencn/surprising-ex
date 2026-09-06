package com.surprising.aeron.service;

import com.surprising.product.api.ProductLine;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RealtimeWorkloadTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void everyCommittedFillProducesOnePublicTradeAndTwoPrivateExecutions(ProductLine product)
            throws Exception {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.productLine = product;
        workload.accountLanes = 4;
        workload.batchSize = 20;
        workload.maxInFlight = 256;
        workload.realtime = false;
        workload.setup();
        try {
            var field = workload.getClass().getDeclaredField("service");
            field.setAccessible(true);
            var service = (SurprisingClusteredService) field.get(workload);
            var outbox = new com.surprising.aeron.client.RealtimeOutbox(65536, 32 * 1024 * 1024);
            service.attachRealtimeForTest(outbox);
            workload.runRoundTripTrades();
            int trades = 0, executions = 0, begins = 0, ends = 0;
            var ids = new java.util.HashSet<String>();
            byte[] bytes;
            while ((bytes = outbox.poll()) != null) {
                var frame = com.surprising.aeron.protocol.RealtimeFrameCodec.decode(bytes);
                org.assertj.core.api.Assertions.assertThat(frame.productLine()).isEqualTo(product);
                switch (frame.kind()) {
                    case TRADE -> {
                        trades++;
                        org.assertj.core.api.Assertions.assertThat(frame.userId()).isZero();
                        ids.add(frame.entityId());
                    }
                    case EXECUTION -> {
                        executions++;
                        org.assertj.core.api.Assertions.assertThat(frame.userId())
                                .isBetween(1000L, 1256L);
                    }
                    case COMMIT_BEGIN -> begins++;
                    case COMMIT_END -> ends++;
                    default -> {}
                }
            }
            org.assertj.core.api.Assertions.assertThat(trades).isEqualTo(512);
            org.assertj.core.api.Assertions.assertThat(ids).hasSize(512);
            org.assertj.core.api.Assertions.assertThat(executions).isEqualTo(1024);
            org.assertj.core.api.Assertions.assertThat(begins).isEqualTo(1024).isEqualTo(ends);
            org.assertj.core.api.Assertions.assertThat(outbox.droppedBatches()).isZero();
        } finally {
            workload.close();
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void roundTripTradesKeepFundsAndSnapshotCorrectWithRealtimeEnabled(ProductLine product) {
        var workload = new ClusteredBatchTradingBenchmark.Workload();
        workload.productLine = product;
        workload.accountLanes = 4;
        workload.batchSize = 20;
        workload.maxInFlight = 256;
        workload.realtime = true;
        workload.setup();
        try {
            workload.runRoundTripTrades();
        } finally {
            workload.close();
        }
    }
}
