package com.surprising.gateway.c2c.task;

import com.surprising.gateway.c2c.service.C2cMerchantBondService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只触发持久质押确认；状态与资金意图判断由Service事务聚合。 */
@Component
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public class C2cMerchantBondTask {
    private final C2cMerchantBondService bonds;
    public C2cMerchantBondTask(C2cMerchantBondService bonds) {this.bonds=bonds;}
    @Scheduled(fixedDelayString="${surprising.c2c.settlement-delay-ms:5000}",scheduler="c2cSettlementScheduler")
    public void confirm() {bonds.confirm();}
}
