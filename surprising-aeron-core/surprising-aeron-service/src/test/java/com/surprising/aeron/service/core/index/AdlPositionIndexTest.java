package com.surprising.aeron.service.core.index;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.position.RuntimePositionIndexValue;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * ADL 持仓候选索引的成员与复用测试，检查原对象和成员集合可保留，但最新方向、数量、资产归属及查询上限必须正确。
 */
class AdlPositionIndexTest {
    /**
     * 同一资产内反复改变持仓方向和数量，索引值对象及成员集合应复用，读取到的数量必须总是最新，成员数始终只有一个。
     */
    @Test
    void retainsMembershipStorageButPublishesLatestQuantityAndDirection() throws Exception {
        // 本用例的独立 ADL 持仓索引，后续加入、修改和查询都在它上面执行。
        var index = emptyIndex();
        // 首次加入的 USDT 多头索引值，建立需要复用的候选成员。
        var previous = position("USDT", 1);
        index.apply(1, null, previous);
        // 索引内部资产成员映射的反射字段，用来核对成员集合引用没有反复替换。
        var field = AdlPositionIndex.class.getDeclaredField("keysByAsset");
        field.setAccessible(true);
        // 按资产代码保存成员集合的内部映射，只在测试中借反射查看对象身份。
        var assets = (java.util.Map<?, ?>) field.get(index);
        // 最初的 USDT 成员集合引用，多空数量更新后应仍然是同一个集合。
        Object members = assets.get("USDT");
        // 索引内部保存并复用的当前值对象，每次更新应原位反映最新带方向数量。
        var stored = index.value(1);
        for (/* 循环测试的绝对数量，按奇偶切换正负号来反复跨越多空方向。 */ int quantity = 2; quantity < 100; quantity++) {
            // 本轮要发布的新数量和方向索引值，写入后内部存储仍应复用原对象。
            var current = position("USDT", quantity % 2 == 0 ? quantity : -quantity);
            index.apply(1, index.value(1), current);
            assertThat(index.value(1)).isSameAs(stored);
            assertThat(stored.signedQuantitySteps()).isEqualTo(current.signedQuantitySteps());
            assertThat(assets.get("USDT")).isSameAs(members);
            assertThat(index.positions("USDT", 2)).hasSize(1);
        }
    }

    /**
     * 依次测试平仓、重开、改结算资产和删除，候选成员必须随有仓状态及资产迁移，空仓记录不能出现在 ADL 候选中。
     */
    @Test
    void updatesMembershipOnCloseReopenAssetChangeAndRemoval() {
        // 本用例的独立 ADL 持仓索引，后续加入、修改和查询都在它上面执行。
        var index = emptyIndex();
        // 有十步 USDT 多头的索引值，平仓后可用它再次重开。
        var open = position("USDT", 10);
        // 数量为 0 的 USDT 空仓索引值，保留值记录但从候选成员中移除。
        var closed = position("USDT", 0);
        // 资产改为 BTC 且数量为负五步的新值，候选成员应从 USDT 移到 BTC。
        var moved = position("BTC", -5);
        index.apply(1, null, open);
        index.apply(1, index.value(1), closed);
        assertThat(index.positions("USDT", 2)).isEmpty();
        assertThat(index.value(1).signedQuantitySteps()).isZero();
        index.apply(1, index.value(1), open);
        assertThat(index.positions("USDT", 2)).hasSize(1);
        index.apply(1, index.value(1), moved);
        assertThat(index.positions("USDT", 2)).isEmpty();
        assertThat(index.positions("BTC", 2)).hasSize(1);
        index.apply(1, index.value(1), null);
        assertThat(index.positions("BTC", 2)).isEmpty();
        assertThat(index.value(1)).isNull();
        index.apply(1, null, closed);
        index.apply(1, index.value(1), position("BTC", 0));
        assertThat(index.positions("BTC", 2)).isEmpty();
    }

    /**
     * 使用另一索引中的同字段对象冒充旧值时必须拒绝，当前持仓和其他用户成员保持有效；随后用真正旧值删除一项，不能损坏另一持仓。
     */
    @Test
    void rejectsStalePreviousWithoutDamagingOtherPositionsOrCurrentValue() {
        // 本用例的独立 ADL 持仓索引，后续加入、修改和查询都在它上面执行。
        var index = emptyIndex();
        // 用户 7 的第一份 USDT 持仓，用来测试错误旧值被拒绝后仍保持原内容。
        var first = position("USDT", 1);
        // 另一用户 8 的 USDT 持仓，删除第一份持仓不能影响它。
        var second = new RuntimePositionIndexValue(8, "1", "USDT", CorePositionSide.NET, 2);
        index.apply(1, null, first);
        index.apply(2, null, second);
        // 另建的独立索引，其中的同字段对象不是当前索引认可的旧值。
        var unrelated = emptyIndex();
        unrelated.apply(1, null, position("USDT", 1));
        assertThatThrownBy(() -> index.apply(1, unrelated.value(1), null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(index.value(1).signedQuantitySteps()).isEqualTo(1);
        assertThat(index.positions("USDT", 2)).hasSize(2);
        index.apply(1, index.value(1), null);
        assertThat(index.value(2).userId()).isEqualTo(8);
        assertThat(index.value(2).signedQuantitySteps()).isEqualTo(2);
        assertThat(index.positions("USDT", 2)).hasSize(1);
    }

    /**
     * 查询限额小于候选数时应在物化路由前拒绝；足够限额时返回按确定顺序排列的用户，查询不存在资产则返回空。
     */
    @Test
    void checksQueryScanLimitBeforeMaterializingCandidateRoutes() {
        // 本用例的独立 ADL 持仓索引，后续加入、修改和查询都在它上面执行。
        var index = emptyIndex();
        index.apply(1, null, position("USDT", 1));
        index.apply(2, null, new RuntimePositionIndexValue(1, "1", "USDT", CorePositionSide.NET, 2));
        assertThatThrownBy(() -> index.positions("USDT", 1))
                .isInstanceOf(com.surprising.aeron.service.query.state.RuntimeOperationalQueryService.QueryTooLargeException.class);
        assertThat(index.positions("USDT", 2)).extracting(AdlPositionIndex.PositionKey::userId).containsExactly(1L, 7L);
        assertThat(index.positions("BTC", 1)).isEmpty();
    }

    /**
     * 从空线性永续业务状态创建一份独立 ADL 候选索引和身份映射。
     */
    private static AdlPositionIndex emptyIndex() {
        return new AdlPositionIndex(TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL),
                new RuntimeIdentityRegistry());
    }

    /**
     * 创建用户 7、合约 1 的净持仓索引值，允许传正负或 0 数量以测试开仓、多空转换和平仓。
     * @param asset 本索引值所属的结算资产代码，候选集合按它分组
     * @param quantity 带多空符号的持仓数量步数，0 表示不应进入候选集合的空仓
     */
    private static RuntimePositionIndexValue position(String asset, long quantity) {
        return new RuntimePositionIndexValue(7, "1", asset, CorePositionSide.NET, quantity);
    }
}
