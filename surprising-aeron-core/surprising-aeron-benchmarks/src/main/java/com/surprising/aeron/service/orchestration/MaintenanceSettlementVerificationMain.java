package com.surprising.aeron.service.orchestration;

import lombok.extern.slf4j.Slf4j;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.product.api.ProductLine;

/** Finite correctness verification, without a load generator or throughput measurements. */
@Slf4j
public final class MaintenanceSettlementVerificationMain {
    private MaintenanceSettlementVerificationMain() { }
    public static void main(String[] args) {
        for (ProductLine line : ProductLine.values()) {
            if (line == ProductLine.SPOT) continue;
            for (CoreMarginMode margin : CoreMarginMode.values()) {
                var scenario = new SettlementSolvencyBenchmark();
                scenario.productLine=line; scenario.marginMode=margin;
                scenario.maxInFlight=256; scenario.settlementTrigger="MAINTENANCE";
                scenario.prepare(); scenario.restore();
                scenario.run(true); scenario.verify();
                log.info("{}", "PASS "+line+" "+margin+": insurance pause/refill, replay, balances, positions and snapshot");
            }
        }
    }
}
