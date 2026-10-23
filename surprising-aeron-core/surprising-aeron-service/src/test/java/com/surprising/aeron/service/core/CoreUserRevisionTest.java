package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/**
 * 核对账户准入、成交及终态释放各自增加的精确用户修订数。对同向加仓与第二轮反向平仓分别验证，同时检查总资金、持仓保证金和预留最终状态。
 */
class CoreUserRevisionTest {
    /**
     * TIME：本用例固定业务毫秒时间。
     * BALANCE：各用户初始资金一百万 USDT 单位。
     */
    private static final long TIME = 1_700_000_000_000L, BALANCE = 1_000_000;
    /** 本测试原命令递增来源序号。 */
    private long sequence;

    /**
     * 两用户各入账后提交两组成交；首次挂单用户修订二且预留十一，每组成交后按双方角色核对既有计数公式。反向第二组还要清零持仓，所有轮次预留清空且冻结等于持仓保证金。
     * @param reverse 第二组是否反向成交以平掉第一组持仓
     */
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void admissionFillAndTerminalReleaseKeepTheirExactRevisionCounts(boolean reverse) {
        try (/* 本用例实际执行并导出提交状态的交易运行时 */ var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            send(core, CoreMessageType.REGISTER_INSTRUMENT, 0, TradingCommandCodec.encodeRegisterInstrument(
                    new RegisterInstrumentCommand(
                            "1", "BTC", "USDT", "USDT", 1, 1, 1,
                            new CoreInstrumentRiskLimits(
                                    100_000, (1_000_000_000_000L / (100_000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100_000)), 100_000, 50_000))),
                            0, 0, 0, -1, 0)));
            send(core, CoreMessageType.APPLY_MARK_PRICE, 0, TradingCommandCodec.encodeApplyMarkPrice(
                    new ApplyMarkPriceCommand("1", 100, 1, TIME)));
            for (long /* 本轮被入账或下单的用户七或八 */ user : new long[]{7, 8}) {
                send(core, CoreMessageType.ADJUST_BALANCE, user, TradingCommandCodec.encodeBalanceAdjustment(
                        new BalanceAdjustmentCommand("USDT", BALANCE)));
                assertThat(CoreTestCompletion.userState(core, user).revision()).isOne();
            }
            for (/* 四条下单消息的下标，每两条组成一次成交 */ int index = 0; index < 4; index++) {
                // 本轮被入账或下单的用户七或八
                long user = (index & 1) == 0 ? 7 : 8;
                // 本条是否卖出，第二组可按 reverse 交换方向
                boolean sell = ((index & 1) == 0) != (reverse && index >= 2);
                send(core, CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(
                        new PlaceOrderCommand(100 + index, "1", sell ? CoreOrderSide.SELL : CoreOrderSide.BUY,
                                100, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                                CoreTimeInForce.GTC, false, "revision-" + index)));
                if (index == 0) {
                    // 首个挂单完成后的用户七状态，用来单独核对准入修订与预留
                    var resting = CoreTestCompletion.userState(core, 7);
                    assertThat(resting.revision()).isEqualTo(2);
                    assertThat(resting.reservations()).singleElement()
                            .satisfies(/* 首次挂单唯一的预留视图，检查预留总额十一 */ v -> assertThat(v.reservedUnits()).isEqualTo(11));
                }
                if ((index & 1) == 0) continue;
                // 截至当前已经完成的成交组数
                int pairs = (index + 1) / 2;
                for (long /* 当前要核对精确修订的用户 ID */ uid : new long[]{7, 8}) {
                    // 当前用户的已提交查询视图
                    var state = CoreTestCompletion.userState(core, uid);
                    // 按用户角色、成交组数及第二组反向条件算出的预期修订
                    long expected = uid == 7 ? 1 + 3L * pairs : 1 + 2L * pairs;
                    if (uid == 8 && reverse && pairs == 2) expected++;
                    assertThat(state.revision()).as("user=%s pairs=%s", uid, pairs).isEqualTo(expected);
                    assertThat(state.positionMode()).isEqualTo(CorePositionMode.ONE_WAY);
                    assertThat(state.reservations()).isEmpty();
                    // 当前用户全部持仓保证金之和，余额冻结应等于它
                    long margin = state.positions().stream().mapToLong(CorePositionView::positionMarginUnits).sum();
                    assertThat(state.balances()).singleElement().satisfies(/* 当前用户唯一 USDT 余额视图，核对总额与冻结 */ balance -> {
                        assertThat(balance.availableUnits() + balance.lockedUnits()).isEqualTo(BALANCE);
                        assertThat(balance.lockedUnits()).isEqualTo(margin);
                    });
                    if (reverse && pairs == 2)
                        assertThat(state.positions()).allSatisfy(/* 反向第二组完成后当前要检查归零的持仓视图 */ p -> assertThat(p.signedQuantitySteps()).isZero());
                }
            }
        }
    }

    /**
     * 以本测试递增来源、固定时间构造 U 本位永续命令，等最终应用并断言 APPLIED。
     * @param core 本用例 U 本位永续运行时
     * @param type 待编码发送的业务消息类型
     * @param user 原命令所属用户，配置命令可为零
     * @param payload 原业务命令已编码载荷
     */
    private void send(TradingCoreRuntime core, CoreMessageType type, long user, byte[] payload) {
        // 为原消息递增的来源序号，同时组成固定 UUID 和关联号
        long id = ++sequence;
        // 本次带固定产品线与时间的原业务消息
        var message = new CoreMessage(CoreMessageHeader.command(type, new UUID(948, id),
                ProductLine.LINEAR_PERPETUAL, CommandSource.OPERATIONS, 948, id, user, TIME, id), payload);
        assertThat(CoreTestCompletion.applyAsynchronously(core, message).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
    }
}
