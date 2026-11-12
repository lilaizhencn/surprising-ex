package com.surprising.aeron.service.query;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPrice;
import java.util.Map;

/** 同一次查询共用的市场输入，由 Owner 捕获配置查询副本、行情引用和资产编号，派发后只读使用。 */
public final class MarketInputs {
    /** Owner 捕获的本次查询配置映射，账户 Lane 只按整数币对键读取。 */
    private final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument> instruments;
    /** Owner 捕获的本次查询行情引用映射，不随之后的行情替换而更换引用。 */
    private final org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<MarkPrice> marks;
    final java.util.Map<String, Integer> assetIds;

    /** 保存本次查询专用的配置和行情映射，并复制资产编号映射为不可修改 Map。 */
    private MarketInputs(org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument> instruments,
            org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<MarkPrice> marks,
            java.util.Map<String, Integer> assetIds) {
        this.instruments = instruments;
        this.marks = marks;
        this.assetIds = java.util.Map.copyOf(assetIds);
    }

    /** Owner 逐币对捕获当前配置的查询副本与行情引用，跳过未知或重复币对，并记录可用的结算资产编号，让后续账户查询不受 Owner 配置更新影响。 */
    public static MarketInputs capture(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
            Iterable<String> instrumentIds) {
        var instruments = new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<CoreInstrument>();
        var marks = new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<MarkPrice>();
        var assets = new java.util.HashMap<String, Integer>();
        for (String instrumentId : instrumentIds) {
            Integer symbolId = identities.findSymbolId(instrumentId);
            if (symbolId == null || instruments.containsKey(symbolId)) continue;
            CoreInstrument instrument = runtime.instrument(instrumentId);
            instruments.put(symbolId, instrument == null ? null : instrument.captureForQuery());
            marks.put(symbolId, runtime.markPrice(symbolId));
            Integer assetId = instrument == null ? null : identities.findAssetId(instrument.settleAsset());
            if (assetId != null) assets.put(instrument.settleAsset(), assetId);
        }
        return new MarketInputs(instruments, marks, assets);
    }

    CoreInstrument instrument(int symbolId) { return instruments.get(symbolId); }
    MarkPrice mark(int symbolId) { return marks.get(symbolId); }
}
