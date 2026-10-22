package com.surprising.aeron.service.matcher;

import com.surprising.aeron.protocol.CoreBookLevelView;
import java.util.List;

/**
 * 一次订单簿初始化或读取所需的币对列表和盘口档位，交接后列表本身不再允许修改。
 * @param symbols 这份订单簿数据涉及的币对列表
 * @param levels 各币对对应的买卖盘口档位，档位携带自己的价格和数量信息
 */
public record BookBootstrapSnapshot(List<String> symbols, List<CoreBookLevelView> levels) {

    /** 检查两个列表都存在，并复制列表结构，防止外部增删元素影响已经交接的盘口数据。 */
    public BookBootstrapSnapshot {
        if (symbols == null || levels == null) {
            throw new IllegalArgumentException("invalid book bootstrap snapshot");
        }
        symbols = List.copyOf(symbols);
        levels = List.copyOf(levels);
    }
}
