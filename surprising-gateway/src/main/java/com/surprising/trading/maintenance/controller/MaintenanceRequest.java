package com.surprising.trading.maintenance.controller;

import com.surprising.trading.maintenance.model.MaintenanceCommand.Mode;
import java.util.UUID;

/** HTTP中的64位标识与价格以字符串绑定，Controller转换为原生业务命令。 */
public record MaintenanceRequest(UUID requestId, String instrumentId, String userId, Mode mode,
                                 String priceTicks, String reason) { }
