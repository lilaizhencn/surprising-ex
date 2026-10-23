package com.surprising.aeron.service.core;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.ApplyFundingCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.ContinueRiskScanCommand;
import com.surprising.aeron.protocol.CoreInstrumentMaintenance;
import com.surprising.aeron.protocol.CoreMaintenanceCodec;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreSettlementProgressCodec;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.RegisterInstrumentCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.order.CoreTestCompletion;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通过真实命令入口验证六产品线的维护门禁、任务归属及快照恢复，并验证五条衍生品线的固定价格分段清仓。辅助方法同步等待控制与撮合完成，不覆盖实际运维网络或自动调度器。
 */
class CoreMaintenanceTest {
    /** 本测试实例的命令来源序号，每次构造新命令时递增，不是生产复制日志位置。 */
    private long sequence;

    /**
     * 建立已成交及未完订单后停牌，确认保留标的外壳、维护状态和资金哈希；同配置重发及快照恢复都不解除停牌，正确任务释放后才允许新任务接管。
     * @param line 当前参数化用例的独立产品线
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void statusOnlyChangesPreserveOpenStateMathAndRecoverWithoutReleasingMaintenance(ProductLine line) {
        try (/* 当前产品线的原运行时，测试结束自动关闭 */ var state=fixture(line)) {
            applied(state,command(line,11,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(301,false,CoreOrderSide.SELL))));
            applied(state,command(line,22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(302,false,CoreOrderSide.BUY))));
            applied(state,command(line,22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(303,false,CoreOrderSide.BUY))));
            // 设置维护前后需要保持的资金哈希基线
            long funds=com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(state.tradingState());
            // 进入停牌前保存的原标的引用，用于验证维护及同配置命令不替换外壳
            var instrument=state.tradingState().instruments().get("1");
            applied(state,gate(line,0,new CoreInstrumentMaintenance(2,CoreInstrumentMaintenance.Mode.HALTED,0)));
            assertThat(state.tradingState().instruments().get("1")).isSameAs(instrument);
            assertThat(instrument.maintenance()).isEqualTo(
                    new CoreInstrumentMaintenance(2,CoreInstrumentMaintenance.Mode.HALTED,0));
            assertThat(com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(state.tradingState())).isEqualTo(funds);
            // 门禁期间尝试开仓得到的拒绝响应
            var denied=apply(state,command(line,22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(304,false,CoreOrderSide.BUY))));
            assertThat(denied.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            // 停牌期间重新注册当前合约和风险配置的响应，成功不表示解除维护
            var sameConfig=apply(state,command(line,0,CoreMessageType.REGISTER_INSTRUMENT,
                    TradingCommandCodec.encodeRegisterInstrument(config(instrument))));
            assertThat(sameConfig.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
            try (/* 从维护或清仓中间快照恢复的运行时，离开作用域关闭 */ var restored=TradingCoreRuntime.fromSnapshot(line,state.snapshot(501))) {
                // 恢复后查询得到的标的外壳，用于检查维护任务状态
                var recovered=restored.tradingState().instruments().get("1");
                assertThat(recovered.maintenance()).isEqualTo(
                        new CoreInstrumentMaintenance(2,CoreInstrumentMaintenance.Mode.HALTED,0));
                assertThat(com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(restored.tradingState())).isEqualTo(funds);
                assertThat(apply(restored,command(line,22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(305,false,CoreOrderSide.BUY)))).commandStatus()).isEqualTo(ResponseStatus.REJECTED);
                applied(restored,gate(line,2,CoreInstrumentMaintenance.TRADING));
                applied(restored,command(line,22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(306,false,CoreOrderSide.BUY))));
                // 释放维护后原任务归属回到零，新任务才可以再次接管。
                applied(restored,gate(line,0,
                        new CoreInstrumentMaintenance(3,CoreInstrumentMaintenance.Mode.HALTED,0)));
                assertThat(recovered.maintenance()).isEqualTo(
                        new CoreInstrumentMaintenance(3,CoreInstrumentMaintenance.Mode.HALTED,0));
            }
        }
    }

    /**
     * 从当前标的复制本用例使用的合约、费率及风险字段，生成注册命令；未显式传入的交易开关、数量步长和保护规则沿用该协议重载的默认值。
     * @param v 需要复制合约、费率及风险字段的原标的
     */
    private static RegisterInstrumentCommand config(com.surprising.aeron.service.instrument.CoreInstrument v) {
        return new RegisterInstrumentCommand(v.instrumentId(),v.contractType().ordinal(),v.baseAsset(),v.quoteAsset(),v.settleAsset(),
                v.notionalMultiplierUnits(),v.priceTickUnits(),v.settleScaleUnits(),v.initialMarginRatePpm(),v.maintenanceMarginRatePpm(),
                v.makerFeeRatePpm(),v.takerFeeRatePpm(),v.expiryEpochMillis(),v.optionType()==null?-1:v.optionType().ordinal(),v.strikePriceTicks(),
                v.maxLeveragePpm(),v.maxPositionNotionalUnits(),v.userOpenInterestLimitRatePpm(),v.userOpenInterestLimitFloorUnits(),
                v.riskLimitBrackets());
    }

    /**
     * 现货用停牌、衍生品用只减仓门禁，确认开仓被拒且资金不变；恢复后错误任务不能释放门禁，原任务可以释放。
     * @param line 当前参数化用例的独立产品线
     */
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void gateSurvivesSnapshotRejectsOpeningAndRequiresTaskOwnership(ProductLine line) {
        try (/* 当前产品线的原运行时，测试结束自动关闭 */ var state = fixture(line)) {
            // 现货选择停牌，衍生品选择只减仓的目标维护模式
            var mode = line == ProductLine.SPOT ? CoreInstrumentMaintenance.Mode.HALTED : CoreInstrumentMaintenance.Mode.REDUCE_ONLY;
            applied(state,gate(line,0,new CoreInstrumentMaintenance(701,mode,0)));
            // 设置维护前后需要保持的资金哈希基线
            long funds = com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(state.tradingState());
            // 门禁期间尝试开仓得到的拒绝响应
            var denied = apply(state,command(line,22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(200,false,CoreOrderSide.BUY))));
            assertThat(denied.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(com.surprising.aeron.service.snapshot.state.FundsStateHash.compute(state.tradingState())).isEqualTo(funds);
            try (/* 从维护或清仓中间快照恢复的运行时，离开作用域关闭 */ var restored = TradingCoreRuntime.fromSnapshot(line,state.snapshot(500))) {
                assertThat(restored.tradingState().instruments().get("1").maintenance()).isEqualTo(new CoreInstrumentMaintenance(701,mode,0));
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
                // 使用错误原任务 ID 尝试释放门禁得到的响应
                var wrongTask = apply(restored,gate(line,702,CoreInstrumentMaintenance.TRADING));
                assertThat(wrongTask.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
                applied(restored,gate(line,701,CoreInstrumentMaintenance.TRADING));
                applied(restored,command(line,22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(201,false,CoreOrderSide.BUY))));
            }
        }
    }

    /**
     * 五条衍生品线建立买卖对手成交，固定清仓价后验证风险扫描不派强平、永续资金费及改价结算被拒；首段后快照恢复并逐段重复提交，最终持仓和锁定归零、总资金不变，关闭后不能恢复交易。操作用户一不是结算参与者。
     * @param line 当前参数化用例的独立产品线
     */
    @ParameterizedTest
    @EnumSource(value=ProductLine.class,names={"SPOT"},mode=EnumSource.Mode.EXCLUDE)
    void fixedPriceClearanceIsFundedIdempotentAndRecoversBetweenChunks(ProductLine line) {
        try (/* 当前产品线的原运行时，测试结束自动关闭 */ var state = fixture(line)) {
            applied(state,command(line,11,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(101,false,CoreOrderSide.SELL))));
            applied(state,command(line,22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(102,false,CoreOrderSide.BUY))));
            // 清仓前按结算资产计算的全系统资金基线
            long initialTotal = total(state,line);
            applied(state,gate(line,0,new CoreInstrumentMaintenance(801,CoreInstrumentMaintenance.Mode.SETTLEMENT,120)));
            // 该产品线对应的合约类型，决定反向资产、期权价格及永续资金费分支
            var type=ContractType.valueOf(line.contractTypeCode());
            applied(state,command(line,0,CoreMessageType.APPLY_MARK_PRICE,TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                    ? new ApplyMarkPriceCommand("1",10_000,10_000,10_000,2,1_700_000_000_000L+sequence+1)
                    : new ApplyMarkPriceCommand("1",10_000,2,1_700_000_000_000L+sequence+1))));
            applied(state,command(line,0,CoreMessageType.CONTINUE_RISK_SCAN,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(16))));
            assertThat(state.tradingState().riskState().liquidations()).isEmpty();
            if (type.isPerpetual()) {
                assertThat(apply(state,command(line,0,CoreMessageType.APPLY_FUNDING,
                        TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(899,"1",10_000)))).commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            }
            // 使用不同于固定清仓价的结算请求应被拒绝，不能据此清除持仓。
            // 把固定清仓价一百二十改成一百二十一的结算请求响应，预期拒绝
            var repriced = apply(state,command(line,0,CoreMessageType.SETTLE_INSTRUMENT,
                    TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(801,"1",121,0))));
            assertThat(repriced.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            // 操作用户不自动成为结算参与者；首段和恢复续步都使用非零操作者，避免引入无关账户 Lane。
            // 由非参与者用户一发起的首段固定价清仓成功响应
            var first = applied(state,command(line,1,CoreMessageType.SETTLE_INSTRUMENT,
                    TradingCommandCodec.encodeSettleInstrument(new SettleInstrumentCommand(801,"1",120,0,0,1,0,1))));
            // 本次成功响应解出的清仓续步游标和完成标记
            var progress = CoreSettlementProgressCodec.decode(first.data());
            try (/* 从维护或清仓中间快照恢复的运行时，离开作用域关闭 */ var restored = TradingCoreRuntime.fromSnapshot(line,state.snapshot(700))) {
                // 恢复后推进页数的保护计数，必须小于二十以发现无法收敛
                int steps = 0;
                while (!progress.complete()) {
                    assertThat(++steps).isLessThan(20);
                    // 按上段用户与订单游标生成的当前清仓续步命令，同一对象再次提交验证 UUID 去重
                    var message = command(line,1,CoreMessageType.SETTLE_INSTRUMENT,TradingCommandCodec.encodeSettleInstrument(
                            new SettleInstrumentCommand(801,"1",120,0,progress.nextCursorUserId(),1,progress.nextCursorOrderId(),1)));
                    // 本次执行或撮合补齐得到的响应，辅助方法继续检查其状态
                    var response = applied(restored,message);
                    progress = CoreSettlementProgressCodec.decode(response.data());
                    assertThat(apply(restored,message).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                }
                assertThat(restored.tradingState().users().values()).allSatisfy(/* 清仓完成后逐个检查的用户状态 */ user -> {
                    assertThat(user.positions().values()).allSatisfy(/* 该用户的某个持仓，清仓后数量应为零 */ p -> assertThat(p.signedQuantitySteps()).isZero());
                    assertThat(user.balances().values()).allSatisfy(/* 该用户的某项资产余额，清仓后锁定应为零 */ balance -> assertThat(balance.lockedUnits()).isZero());
                });
                assertThat(total(restored,line)).isEqualTo(initialTotal);
                applied(restored,gate(line,801,new CoreInstrumentMaintenance(801,CoreInstrumentMaintenance.Mode.CLOSED,120)));
                assertThat(apply(restored,gate(line,801,CoreInstrumentMaintenance.TRADING)).commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            }
        }
    }

    /**
     * 创建本产品线标的、初始价格和用户十一、二十二的资金，供维护及清仓测试复用；返回的运行时由调用方关闭。
     * @param line 当前参数化用例的独立产品线
     */
    private TradingCoreRuntime fixture(ProductLine line) {
        // 当前产品线的新运行时，成功返回后由调用方的资源作用域关闭
        var state = new TradingCoreRuntime(line);
        // 该产品线对应的合约类型，决定反向资产、期权价格及永续资金费分支
        var type = ContractType.valueOf(line.contractTypeCode());
        // 该产品线采用的结算资产，反向为 BTC，其余为 USDT
        String asset = type.isInverse() ? "BTC" : "USDT";
        applied(state,command(line,1,CoreMessageType.REGISTER_INSTRUMENT,TradingCommandCodec.encodeRegisterInstrument(
                new RegisterInstrumentCommand("1",type.ordinal(),"BTC","USDT",asset,1,1,type.isInverse()?1_000:1,
                        100_000,50_000,0,0,type.isDelivery()||type.isOption()?2_000_000_000_000L:0,type.isOption()?0:-1,type.isOption()?100:0))));
        applied(state,command(line,1,CoreMessageType.APPLY_MARK_PRICE,TradingCommandCodec.encodeApplyMarkPrice(type.isOption()
                ? new ApplyMarkPriceCommand("1",100,100,100,1,1_700_000_000_000L)
                : new ApplyMarkPriceCommand("1",100,1,1_700_000_000_000L))));
        applied(state,command(line,11,CoreMessageType.ADJUST_BALANCE,TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(line==ProductLine.SPOT?"BTC":asset,20_000))));
        applied(state,command(line,22,CoreMessageType.ADJUST_BALANCE,TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset,20_000))));
        return state;
    }
    /**
     * 按该产品线结算资产合计用户总资金、手续费、保险、清算损益及舍入余额，用于清仓前后守恒比较。
     * @param state 本用例负责关闭的交易运行时
     * @param line 当前参数化用例的独立产品线
     */
    private static long total(TradingCoreRuntime state, ProductLine line) {
        // 该产品线采用的结算资产，反向为 BTC，其余为 USDT
        String asset = ContractType.valueOf(line.contractTypeCode()).isInverse()?"BTC":"USDT";
        // 本次查询得到的财库状态，用于合计各结算资产余额
        var t = state.tradingState().treasuryState();
        return state.tradingState().users().values().stream().mapToLong(/* 参与结算资产总额合计的用户 */ u -> u.totalUnits(asset)).sum()
                + t.feeBalances().getOrDefault(asset,0L)+t.insuranceBalances().getOrDefault(asset,0L)
                + t.clearingPnlBalances().getOrDefault(asset,0L)+t.roundingResidualBalances().getOrDefault(asset,0L);
    }
    /**
     * 为标的一构造维护命令，携带调用方预期的原任务 ID 和目标状态，实际归属校验在业务转换中执行。
     * @param line 当前参数化用例的独立产品线
     * @param expected 维护转换要求匹配的原任务 ID，零表示当前没有任务占用
     * @param value 需要写入的维护模式、任务身份和清仓价
     */
    private CoreMessage gate(ProductLine line,long expected,CoreInstrumentMaintenance value) {
        return command(line,0,CoreMessageType.UPDATE_INSTRUMENT_MAINTENANCE,
                CoreMaintenanceCodec.encodeCommand(new CoreMaintenanceCodec.Command("1",expected,value)));
    }
    /**
     * 递增本测试序号，使用新 UUID 和运维来源构造命令；提交时间与来源序号随该序号增加。
     * @param line 当前参数化用例的独立产品线
     * @param user 本次命令头中的操作者用户 ID；维护和结算操作者不自动成为结算参与者
     * @param type 本次请求消息类型
     * @param payload 该请求已经编码的协议载荷
     */
    private CoreMessage command(ProductLine line,long user,CoreMessageType type,byte[] payload) {
        // 本次新分配的测试命令序号，用作来源序号和确定性递增时间
        long seq = ++sequence;
        return new CoreMessage(CoreMessageHeader.command(type,UUID.randomUUID(),line,CommandSource.OPERATIONS,981,seq,user,1_700_000_000_000L+seq,seq),payload);
    }
    /**
     * 构造标的一、价格一百、数量四的全仓净持仓限价 GTC 委托，订单 ID、只减仓标志和买卖方向由用例指定。
     * @param id 本用例委托的订单 ID
     * @param reduce 本用例委托是否只减仓
     * @param side 本用例委托的买卖方向
     */
    private static PlaceOrderCommand order(long id,boolean reduce,CoreOrderSide side) {
        return new PlaceOrderCommand(id,"1",side,100,4,reduce,CoreMarginMode.CROSS,CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"maint-test-"+id);
    }
    /**
     * 执行并等待该命令，断言终态成功，失败提示包括命令类型和结果码。
     * @param state 本用例负责关闭的交易运行时
     * @param message 本次提交的命令及原时间、来源序号
     */
    private static CoreResponse applied(TradingCoreRuntime state,CoreMessage message) {
        // 本次执行或撮合补齐得到的响应，辅助方法继续检查其状态
        var response = apply(state,message);
        assertThat(response.commandStatus()).as("%s: %s",message.header().messageType(),response.resultCode()).isEqualTo(ResponseStatus.APPLIED);
        return response;
    }
    /**
     * 执行命令，直接控制槽最多轮询五秒；撮合待完成则同步补齐，并排完剩余撮合队列。撮合排空循环本身没有独立期限，返回值依赖该测试入口最终提供响应。
     * @param state 本用例负责关闭的交易运行时
     * @param message 本次提交的命令及原时间、来源序号
     */
    private static CoreResponse apply(TradingCoreRuntime state,CoreMessage message) {
        // 本次执行或撮合补齐得到的响应，辅助方法继续检查其状态
        var response = state.apply(message);
        // 直接控制槽轮询使用的单调时钟五秒期限，撮合补齐另走原同步辅助
        long directDeadline = System.nanoTime() + 5_000_000_000L;
        while (response == null && state.directCommand.active() && System.nanoTime() < directDeadline) {
            response = state.directCommand.poll();
            if (response == null) Thread.onSpinWait();
        }
        if (response == null && state.directCommand.active()) {
            throw new AssertionError("Direct command timed out: " + message.header().messageType());
        }
        if (response.resultCode()==CoreResultCode.MATCHING_PENDING) response = CoreTestCompletion.completeMatchingSynchronously(state, state.matchingSequence(message.header().commandId()),message.header().submittedAtEpochMillis(),message.header().sourceSequence());
        while(state.pendingMatching().firstSequence()!=0) CoreTestCompletion.completeMatchingSynchronously(state, state.pendingMatching().firstSequence(),message.header().submittedAtEpochMillis(),message.header().sourceSequence());
        return response;
    }
}
