package com.surprising.trading.maintenance.model;

/** 持久化最终Core命令字节，重启/未知结果后以原身份与原载荷继续，不依赖命令对象的JSON属性。 */
public record MaintenanceActionPlan(String kind, long userId, long entityId, byte[] commandPayload) {
    public MaintenanceActionPlan {
        commandPayload = commandPayload.clone();
    }

    @Override
    public byte[] commandPayload() {
        return commandPayload.clone();
    }
}
