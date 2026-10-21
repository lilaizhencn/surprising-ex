package com.surprising.aeron.service.state.snapshot;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreTriggerCondition;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.protocol.CoreTriggerPriceSource;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.product.api.ProductLine;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * 用仓库中已发布的二进制样本验证嵌入算法单和触发单的独立快照读取。直接与固定业务值比较，并检查截断和未知版本；不通过当前接口编码器重新生成样本，避免编码与解码同时改变而掩盖格式差异。
 */
class PublishedEmbeddedSnapshotTest {
    /**
     * 读取已发布版本一算法单字节，与固定完整业务值比较；少最后一个字节或把版本改为二都必须拒绝。
     */
    @Test
    void readsPublishedAlgoWithoutDependingOnInterfaceCodec() throws Exception {
        // 算法单样本应恢复的固定业务值，独立于当前接口编码器。
        var expected = new CoreAlgoOrderView(7, 11, "client-7", "1", 0, CoreOrderSide.BUY,
                0, 100, 25, 10, 40, CoreMarginMode.CROSS, CorePositionSide.NET, false, false,
                CoreTimeInForce.IOC, 1, 91, "", 1, 2, 0, 1, 2, 3, List.of(91L), 25, 0, 0);
        // 本次从资源读取的样本字节副本，后续只修改内存版本字节进行拒绝测试。
        byte[] data = read("published-algo-v1.bin");
        assertThat(TradingSnapshotV36Reader.readPublishedAlgo(data)).isEqualTo(expected);
        assertThatThrownBy(() -> TradingSnapshotV36Reader.readPublishedAlgo(Arrays.copyOf(data, data.length - 1)))
                .isInstanceOf(ProtocolException.class);
        data[0] = 2;
        assertThatThrownBy(() -> TradingSnapshotV36Reader.readPublishedAlgo(data)).isInstanceOf(ProtocolException.class);
    }

    /**
     * 读取版本三触发单，核对费用及 LAST 价格源；去掉末尾显式价格源并标成版本二时要求历史定义的 MARK，截断版本三及未知版本四均拒绝。
     */
    @Test
    void readsPublishedTriggerWithFeesAndExplicitPriceSource() throws Exception {
        // 本次从资源读取的样本字节副本，后续只修改内存版本字节进行拒绝测试。
        byte[] data = read("published-trigger-v3.bin");
        assertThat(TradingSnapshotV36Reader.readPublishedTrigger(data)).isEqualTo(expected(CoreTriggerPriceSource.LAST));
        assertThatThrownBy(() -> TradingSnapshotV36Reader.readPublishedTrigger(Arrays.copyOf(data, data.length - 1)))
                .isInstanceOf(ProtocolException.class);
        // 已发布版本二比版本三少末尾显式价格源，历史格式定义的价格源为 MARK。
        // 从版本三移除最后四字节显式价格源并改版本号得到的版本二布局。
        byte[] v2 = Arrays.copyOf(data, data.length - Integer.BYTES);
        v2[0] = 2;
        assertThat(TradingSnapshotV36Reader.readPublishedTrigger(v2)).isEqualTo(expected(CoreTriggerPriceSource.MARK));
        data[0] = 4;
        assertThatThrownBy(() -> TradingSnapshotV36Reader.readPublishedTrigger(data)).isInstanceOf(ProtocolException.class);
    }

    /**
     * 构造固定触发单期望值，除价格来源由参数选择外，保持费用、方向、条件和业务状态一致。
     * @param source 该版本样本应恢复出的触发价格来源
     */
    private static CoreTriggerOrderStateView expected(CoreTriggerPriceSource source) {
        return new CoreTriggerOrderStateView(501, ProductLine.LINEAR_PERPETUAL, 1001,
                "tp-501", "oco-1", "1", CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT,
                CoreTriggerCondition.GREATER_OR_EQUAL, 70000, 0, 0, 0, 0, 0, CoreOrderType.MARKET,
                CoreTimeInForce.IOC, 0, 10, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", 0, 0, 1000, 1000, 1, -25, 40, source);
    }

    /**
     * 从固定恢复样本目录读取指定文件的字节；资源缺失主动报错，读取流在离开作用域时关闭，其他读取异常向调用方传递。
     * @param file 已发布恢复样本目录内的资源文件名
     */
    private static byte[] read(String file) throws Exception {
        try (/* 本次读取已发布二进制样本的输入流，作用域结束自动关闭。 */ var in = PublishedEmbeddedSnapshotTest.class.getResourceAsStream("/recovery/a8df45ae/" + file)) {
            if (in == null) throw new IllegalStateException("missing published fixture: " + file);
            return in.readAllBytes();
        }
    }
}
