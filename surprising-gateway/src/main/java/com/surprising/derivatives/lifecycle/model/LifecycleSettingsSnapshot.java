package com.surprising.derivatives.lifecycle.model;

import com.surprising.derivatives.lifecycle.model.LifecycleBusinessSettings;
import java.time.Instant;

/** 产品线完整生命周期规则的数据库版本，安装完成后作为调度刷新事件。 */
public record LifecycleSettingsSnapshot(long version, LifecycleBusinessSettings settings,
                                        String updatedBy, String reason, Instant updatedAt) { }
