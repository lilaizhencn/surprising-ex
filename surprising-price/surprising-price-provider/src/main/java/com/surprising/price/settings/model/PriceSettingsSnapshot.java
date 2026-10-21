package com.surprising.price.settings.model;

import java.time.Instant;

/** 单条产品线已保存的配置版本，同时作为安装完成事件。 */
public record PriceSettingsSnapshot(long version, PriceBusinessSettings settings, String updatedBy, String reason, Instant updatedAt) { }
