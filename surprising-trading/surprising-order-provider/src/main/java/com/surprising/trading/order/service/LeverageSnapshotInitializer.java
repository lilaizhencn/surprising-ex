package com.surprising.trading.order.service;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.repository.LeverageSettingRepository;
import org.springframework.stereotype.Component;

/**
 * 启动时把用户杠杆配置加载到订单 JVM 快照。
 *
 * <p>数据库只作为启动恢复来源；下单保证金计算完成快照初始化后不再读取杠杆配置表。
 * 后续管理员修改由 {@link LeverageService} 立即更新同一份快照。</p>
 */
@Component
public class LeverageSnapshotInitializer {

    private final LeverageSettingRepository repository;
    private final OrderMarginSnapshotCache marginSnapshotCache;

    public LeverageSnapshotInitializer(LeverageSettingRepository repository,
                                       OrderMarginSnapshotCache marginSnapshotCache) {
        this.repository = repository;
        this.marginSnapshotCache = marginSnapshotCache;
    }

    /** 仅用于启动恢复或显式快照重建，不允许在下单请求中调用。 */
    public void initialize(ProductLine productLine) {
        if (productLine == null) {
            throw new IllegalArgumentException("产品线不能为空");
        }
        for (LeverageSettingRepository.LeverageSnapshot setting : repository.snapshot(productLine)) {
            marginSnapshotCache.putLeverage(productLine, setting.userId(), setting.symbol(),
                    setting.marginMode(), setting.leveragePpm());
        }
    }
}
